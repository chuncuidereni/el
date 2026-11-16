/**
 * 【文件职责】本文件定义数据源管理模块的查询或操作结果模型 SftpDirPreview，用于在请求、业务服务、持久化或 API 展示之间承载结构化数据；不包含远程连接和数据库操作。
 */

package com.el2ck.entity.datasource;

import com.el2ck.entity.common.Column;

import com.el2ck.duck.DuckQueryResult;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * SFTP 目录结构分析结果(SftpDirectoryAnalyzer 的产出，对应 Go 的目录预览)。
 * 一次请求内递归扫描 root 下所有匹配文件，逐个只取前 100 行探测列结构，再按「列名列表」把
 * 同构文件归并成组。核心用途是让前端在导入前就能看到「哪些文件结构一致、哪些对不上」。
 */
@Data
public class SftpDirPreview {
    /** 实际扫描的目录绝对路径。入参为相对路径时会挂到登录后的当前工作目录下，服务端不解析 ".."。 */
    private String root;
    /** 本次生效的匹配模式。入参为空时已回落为 *.csv，故回显的始终是真正用过的那个值。 */
    private String pattern;
    /** 结构基准文件的完整路径：取路径排序后第一个解析成功的文件。空串表示没有任何文件解析成功。 */
    private String reference = "";
    /** 基准文件的列名列表，它同时是「是否同构」的判定依据和分组的键。 */
    private List<String> referenceColumns = new ArrayList<>();
    /** 基准文件的完整列定义(名 + 类型)；类型只影响展示，不参与同构判定。 */
    private List<Column> columns = new ArrayList<>();
    /** 每个匹配文件一条记录；解析失败的也在其中(靠 error 区分)，保证「扫到的文件」与「列出的文件」一致。 */
    private List<FileSchema> files = new ArrayList<>();
    /** 按列名归并出的分组；解析失败的文件不参与归并，各自单独成组。 */
    private List<ColGroup> groups = new ArrayList<>();
    /** 预留的文件总数。SFTP 路径并不回填此字段，恒为初始值 -1；前端实际用 files.length 统计。 */
    private long total = -1;
    /** 基准文件前 10 行的数据预览。探测失败时为 null，靠 NON_NULL 让该键整个从响应里消失。 */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    private DuckQueryResult preview;

    /** 单个文件的探测结果：结构与基准是否一致，以及不一致时的原因。 */
    @Data
    public static class FileSchema {
        /** 本次探测实际采用的编码和分隔符，便于核对自动识别结果。 */
        private String csvEncoding;
        private String csvDelimiter;
        /** 文件的完整远端路径；同时出现在所属分组的 memberFiles 里。 */
        private String path;
        /** 文件字节数，取自目录列举时返回的属性，不再额外发起 stat。 */
        private long size;
        /** 修改时间，已按本机时区格式化为 yyyy-MM-dd HH:mm:ss。 */
        private String mtime;
        /** 该文件自己的列名列表(与基准比对的就是它)。 */
        private List<String> columns = new ArrayList<>();
        /** 列数。与 columns.size 冗余，为对齐 Go 响应结构而保留。 */
        private int columnCount;
        /** 列名是否与基准完全一致。只比列名不看类型：同名列不同类型仍算同构。 */
        private boolean sameSchema;
        /** 解析失败原因；成功时为 null，NON_EMPTY 保证它不会出现在正常文件的 JSON 里。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String error;
    }

    /** 一组列名相同的文件：columns/colNames 取自首个落入该组的文件。 */
    @Data
    public static class ColGroup {
        /** 该组的列定义(名 + 类型)，同一组内各文件列名相同、类型未必相同。 */
        private List<Column> columns = new ArrayList<>();
        /** 分组键：列名列表。失败组的键固定为单个 "(无法解析)"，故不会与正常组撞车。 */
        private List<String> colNames = new ArrayList<>();
        /** 组内文件数，等于 memberFiles.size()。 */
        private int fileCount;
        /** 组内文件的完整路径，前端展开分组时按它逐个展示。 */
        private List<String> memberFiles = new ArrayList<>();
        /** 该组是否为「解析失败」组；正常组为 null，NON_EMPTY 使其不进入 JSON。 */
        @JsonInclude(JsonInclude.Include.NON_EMPTY)
        private String error;
    }
}
