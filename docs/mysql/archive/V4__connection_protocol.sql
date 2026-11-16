-- 连接协议可选化:native(原生 TCP,默认)或 http(HTTP 接口)。
-- V1-V3 已发布,不可修改;结构变化一律新增版本(V4)。

ALTER TABLE `el_connections`
  ADD COLUMN `protocol` VARCHAR(16) NOT NULL DEFAULT 'native'
  COMMENT '连接协议: native=原生TCP(默认) / http=HTTP接口';

-- 说明:默认保持 native,已有连接迁移后行为不变。
-- HTTP 连接的端口通常为 8123(native 为 9000),但端口与协议是两件独立的事,不做联动校验。
