package com.el2ck.util;

import com.el2ck.entity.common.Column;
import java.util.List;

/** 新旧 CSV 读取器共用的流式接口；旧断点仍交给原解析器。 */
public interface CsvRows extends AutoCloseable {
    List<Column> columns();
    List<List<String>> next(int limit);
    void close();
}
