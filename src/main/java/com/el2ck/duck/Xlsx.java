package com.el2ck.duck;

/**
 * 【文件职责】读取工作簿并转换为统一表格数据，处理工作表、表头和单元格值。
 */

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.FormulaEvaluator;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Excel(.xlsx/.xls) → UTF-8 CSV 转换(供内嵌 DuckDB read_csv 解析)。
 *
 * 只取首个工作表;单元格统一按显示文本(DataFormatter)输出，公式取缓存/求值结果;
 * 空行跳过、行内补足到整表最大列数，保证 read_csv auto_detect 推出稳定 schema。
 * 转换结果缓存到 duckdb 库目录 xlsx-cache/，由调用方(SyncEngine/DataSourceService)按 类型+路径 复用。
 */
public final class Xlsx {

    /** 工具类私有构造器：禁止实例化。 */
    private Xlsx() {
    }

    /** 生成 CSV 文本:含逗号/引号/换行的字段加引号，内部双引号翻倍。 */
    // 边界:含换行的字段虽然被引号包住,但 DuckDB read_csv 的引号内换行解析依赖其自身实现,
    // 本方法只保证 CSV 语法本身正确(RFC 4180);被跳过判断的字段是“不需要引号”的普通文本。
    private static void appendField(StringBuilder sb, String v) {
        // 判断是否需要加引号(含逗号/双引号/换行任一即需要)
        boolean needQuote = v.indexOf(',') >= 0 || v.indexOf('"') >= 0
                || v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0;
        if (!needQuote) {
            // 无需引号:直接追加原文本
            sb.append(v);
            return;
        }
        // 需要引号:首尾包双引号，内部双引号翻倍转义
        sb.append('"').append(v.replace("\"", "\"\"")).append('"');
    }

    /**
     * 把 Excel 文件转换为 UTF-8 CSV 到目标路径。
     *
     * @throws IllegalStateException 文件无法解析(非 Excel/损坏/权限等，消息含根因)
     */
    public static void toCsv(File src, File dst) {
        // 缓存全部行，待确定最大列数后再统一输出
        // 为什么要先在内存里攒齐:CSV 各行列数必须一致,而“最大列数”只有在扫完整个工作表后
        // 才知道;若边读边写,后面才发现有更宽的行,前面的行已经写坏了。
        // 边界:整表常驻堆内存,超大 xlsx(几十万行×多列)会显著占用内存,这是本实现的前提。
        List<List<String>> rows = new ArrayList<>();
        // 整表最大列数(用于行尾补空单元格)
        int maxCols = 0;
        // 用 POI 工厂只读打开工作簿(readOnly=true:避免关闭时把改动回写源 xlsx);
        // 公式单元格由下方 FormulaEvaluator 显式求值。
        try (Workbook wb = WorkbookFactory.create(src, null, true)) {
            // 只取第一个工作表
            // 多工作表不做合并/选择:平台的表概念与“一张表=一个文件”对齐,需要其它工作表
            // 应先另存为单独文件;否则会造成“同一文件两次同步拿到不同数据”的歧义。
            Sheet sheet = wb.getSheetAt(0);
            // 用 ROOT locale 做统一数字/日期显示格式，避免依赖本机语言
            DataFormatter fmt = new DataFormatter(Locale.ROOT);
            // 创建公式求值器，用于取公式单元格的计算结果
            FormulaEvaluator eval = wb.getCreationHelper().createFormulaEvaluator();
            // 遍历工作表的每一行
            for (Row row : sheet) {
                // 当前行的单元格序列
                List<String> cells = new ArrayList<>();
                // 最后一个单元格的列号(含空尾列索引)
                // 用 getLastCellNum(最后一个单元格的下标+1)而不是物理单元格数:行中间的空
                // 单元格不会出现在 POI 的物理单元格集合里,用前者才能保证“第 i 列就是第 i 列”,
                // 否则整行数据会发生左移错位。
                int n = row.getLastCellNum();
                // 逐列取单元格并格式化为显示文本
                for (int i = 0; i < n; i++) {
                    Cell c = row.getCell(i);
                    // 空白单元格输出空串，否则取显示文本(公式用求值结果)
                    cells.add(c == null ? "" : fmt.formatCellValue(c, eval));
                }
                // 去掉尾部空单元格(稀疏行)，保证列数一致。
                // 先削尾、后补宽:尾部空列在这里削掉,输出时再按整表最大列数补齐空格,
                // 两个动作合起来才能保证每行恰好 maxCols 列(既不会多出空列,也不会短一截)。
                while (!cells.isEmpty() && cells.get(cells.size() - 1).isEmpty()) {
                    cells.remove(cells.size() - 1);
                }
                // 判断整行是否全为空白
                // 判空用 trim 后的内容:Excel 里常见的“看起来空”的单元格其实是空格/制表符,
                // 若不 trim 会被当成有效数据行写入 CSV,让下游多出大量空行。
                boolean blank = true;
                for (String s : cells) {
                    if (s != null && !s.trim().isEmpty()) {
                        blank = false;
                        break;
                    }
                }
                if (blank) {
                    continue; // 跳过完全空行
                }
                // 收集非空行
                rows.add(cells);
                // 刷新整表最大列数
                if (cells.size() > maxCols) {
                    maxCols = cells.size();
                }
            }
        } catch (Exception e) {
            // 打开/解析 Excel 失败，包装为运行时异常并带根因
            throw new IllegalStateException("解析 Excel 失败: " + msg(e), e);
        }
        // 以 UTF-8 写入目标 CSV 文件
        try (BufferedWriter w = new BufferedWriter(
                new OutputStreamWriter(new FileOutputStream(dst), StandardCharsets.UTF_8))) {
            // 逐行拼装并写出
            // Excel 的首行原样写成 CSV 首行:转换阶段不改写、也不判断它是不是表头——“首行是否
            // 表头”由数据源的 header 配置(或 Duck.detectCsvHeader 的启发式)在读取时决定,
            // 转换器保持所见即所得,避免同一条信息在两处被各自解释。
            for (List<String> row : rows) {
                // 缓冲当前行的 CSV 文本
                StringBuilder sb = new StringBuilder();
                // 按最大列数逐列输出(不足的补空串，保证各行列数一致)
                for (int i = 0; i < maxCols; i++) {
                    if (i > 0) {
                        // 非首列前补逗号分隔
                        sb.append(',');
                    }
                    // 追加字段(空单元格补空串，走自动加引号逻辑)
                    appendField(sb, i < row.size() ? row.get(i) : "");
                }
                // 写当前行文本
                w.write(sb.toString());
                // 换行
                w.newLine();
            }
        } catch (Exception e) {
            // 写出失败，包装为运行时异常并带根因
            throw new IllegalStateException("写入 Excel 转码 CSV 失败: " + msg(e), e);
        }
    }

    /** 提取异常链中第一条非空消息(必要时回退到 String.valueOf)。 */
    private static String msg(Exception e) {
        // 从当前异常开始向上追溯
        Throwable t = e;
        // 找到第一个带非空消息的根因
        while (t.getCause() != null && (t.getMessage() == null || t.getMessage().isEmpty())) {
            t = t.getCause();
        }
        // 取出该条消息
        String m = t.getMessage();
        // 消息仍为空则退回异常的 toString
        return m == null || m.isEmpty() ? String.valueOf(e) : m;
    }
}
