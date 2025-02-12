package com.hccake.ballcat.system.model.vo;

import com.hccake.ballcat.common.i18n.I18nClass;
import com.hccake.ballcat.common.i18n.I18nField;
import io.swagger.annotations.ApiModel;
import io.swagger.annotations.ApiModelProperty;
import lombok.Data;

/**
 * 菜单权限授权对象
 *
 * @author hccake 2021-04-06 17:59:51
 */
@Data
@I18nClass
@ApiModel(value = "菜单权限授权对象")
public class SysMenuGrantVO {

	private static final long serialVersionUID = 1L;

	/**
	 * 菜单ID
	 */
	@ApiModelProperty(value = "菜单ID")
	private Integer id;

	/**
	 * 父级ID
	 */
	@ApiModelProperty(value = "父级ID")
	private Integer parentId;

	/**
	 * 菜单名称
	 */
	@I18nField(condition = "type != 2")
	@ApiModelProperty(value = "菜单名称")
	private String title;

	/**
	 * 菜单类型 （0目录，1菜单，2按钮）
	 */
	@ApiModelProperty(value = "菜单类型 （0目录，1菜单，2按钮）")
	private Integer type;

}