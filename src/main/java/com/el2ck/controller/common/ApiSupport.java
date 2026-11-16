/**
 * 【文件职责】本文件定义HTTP 公共基础设施模块的控制器公共辅助工具 ApiSupport。为控制器提供无状态辅助能力，避免在各端点重复实现公共处理。
 */

package com.el2ck.controller.common;

import com.el2ck.exception.ApiException;
/**
 * Controller 公共工具(对应 Go api 各 *_api.go 顶部的 parseID)。
 */
public final class ApiSupport {

    // 工具类私有构造器:禁止实例化
    /**
     * 禁止实例化工具类。
     */
    private ApiSupport() {
    }

    /**
     * 解析路径参数中的数字 ID。Go 用 strconv.ParseInt,失败返回 400。
     * 这里的消息仅在与前端约定的中文文案体系内即可(前端只展示 message)。
     *
     * @param id 路径参数原始字符串
     * @return 数值 ID
     */
    public static long parseId(String id) {
        // 空值直接判定为非法 ID
        if (id == null) {
            throw ApiException.validation("ID 不合法");
        }
        // 去掉首尾空白后解析为 long
        try {
            return Long.parseLong(id.trim());
        } catch (NumberFormatException e) {
            // 非数字内容同样按非法 ID 抛业务异常(400)
            throw ApiException.validation("ID 不合法");
        }
    }
}
