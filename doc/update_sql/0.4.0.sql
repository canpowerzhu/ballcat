ALTER TABLE sys_role ADD COLUMN `scope_resources` varchar(255) CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci NULL DEFAULT NULL COMMENT '数据范围资源，当数据范围类型为自定义时使用' AFTER `scope_type`;