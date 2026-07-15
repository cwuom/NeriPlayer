# NeriPlayer Native Source License / Native 源码授权说明

This directory contains NeriPlayer native source code, and also includes
third-party native source code used by the build.

本目录包含 NeriPlayer 的 native 源码，也包含随项目构建使用的第三方
native 源码。

## Scope / 授权范围

This extra license only applies to native source code that is owned by,
or can be relicensed by, the NeriPlayer project author.

下面的额外授权仅适用于 NeriPlayer 项目作者拥有版权或有权再授权的
native 源码，包括但不限于：

- `crash/`
- `usb/exclusive/`
- `usb/iso/`
- `usb/pcm/`
- `usb/uac1/`
- `usb/uac2/`

This license does not apply to third-party source code with its own
copyright or license notice.

它不适用于带有独立版权或独立许可证声明的第三方源码，例如：

- `libusb/` keeps following `LGPL-2.1-or-later` as declared in its file headers
- `libusb/` 源码继续遵循文件头中声明的 `LGPL-2.1-or-later`
- Any other file with an explicit third-party copyright or license notice
- 其他明确标注了独立许可证或第三方版权信息的文件

If a file contains both NeriPlayer-owned code and third-party code, the
third-party parts still follow their original licenses.

如果某个文件同时包含 NeriPlayer 自有代码和第三方代码，第三方代码仍按其
原许可证处理。

## External Contributions / 外部贡献

Native contributions from other developers can only be covered by this
alternative license if the contributor explicitly agrees to license that
contribution under both:

其他开发者贡献的 native 源码，只有在贡献者明确同意时，才会被纳入本
替代授权。贡献者需要同意其贡献同时按以下两种方式授权：

- GPL-3.0, as provided in the repository root `LICENSE` file
- 根目录 `LICENSE` 中的 GPL-3.0 协议
- The NeriPlayer Native Attribution License described in this README
- 本 README 中描述的 NeriPlayer Native Attribution License / 署名授权

By submitting a pull request or patch that modifies the NeriPlayer-owned
native source areas listed above, the contributor is expected to grant this
dual license for their contribution. If a contributor does not agree, they
must clearly say so before the contribution is merged.

当贡献者提交修改上述 NeriPlayer 自有 native 源码区域的 pull request 或
patch 时，默认应明确接受这种双授权。如果贡献者不同意，必须在合并前清楚说明。

If a native contribution is accepted without this dual-license grant, that
specific contribution is not covered by the attribution-based closed-source
exception unless the contributor later grants permission.

如果某段 native 贡献没有获得这种双授权许可，除非贡献者之后补充授权，
否则该具体贡献不适用“署名即可闭源使用”的例外。

Third-party source imports must keep their original license notices and are
not covered by this alternative license unless the copyright holder grants
that permission.

引入第三方源码时，必须保留其原始许可证和版权声明；除非版权持有人明确授权，
否则第三方源码不适用本替代授权。

## Attribution License / 署名授权

For the NeriPlayer-owned native source code listed above, the copyright
holder grants the following alternative license in addition to the GPL-3.0
license in the repository root `LICENSE` file.

对于上述 NeriPlayer 自有 native 源码，除了根目录 `LICENSE` 中的
GPL-3.0 授权外，版权持有人额外授予以下替代授权：

You may copy, reference, modify, merge, compile, link, publish, and
redistribute this native source code, including use in closed-source or
commercial projects. A closed-source or commercial project does not need
to be open sourced under the GPL only because it uses this part of the
native source code, as long as all conditions below are met.

你可以复制、引用、修改、合并、编译、链接、发布和再分发这些 native 源码，
包括将其用于闭源或商业项目。只要同时满足下列条件，该闭源或商业项目不需要
仅因为使用这部分 native 源码而按 GPL 协议开源。

Required conditions / 必须满足的条件：

1. Show NeriPlayer project information in a place that end users can easily see
2. 在最终产品中用户容易看到的位置展示 NeriPlayer 项目信息
3. The information must include the project name, project link, and original author
4. 项目信息至少包含项目名称、项目链接和原作者信息
5. Do not imply that NeriPlayer or the original author endorses or certifies your project
6. 不得暗示 NeriPlayer 或原作者为你的项目提供背书、认证或担保
7. Do not remove existing copyright, license, or third-party notices from the source code
8. 不得移除源码中已有的版权、许可证或第三方声明

Recommended notice / 推荐展示格式：

```text
This product uses native source code from NeriPlayer.
Project: https://github.com/cwuom/NeriPlayer
Original author: cwuom / ouom
```

Visible places include, but are not limited to:

“用户容易看到的位置”包括但不限于：

- About page inside the app / 应用内“关于”页面
- Open-source licenses or acknowledgements page / 应用内“开源许可”或“致谢”页面
- Product website, help page, or release notes visible to end users
- 产品官网、帮助页面或发行说明中面向最终用户的显著位置

Putting the notice only in a source repository, build script, internal
developer document, or any other place that normal users usually cannot
see does not satisfy this condition.

仅放在源码仓库、构建脚本、开发者内部文档或用户通常看不到的位置，
不视为满足该条件。

If you cannot or do not want to meet the attribution condition above, the
NeriPlayer-owned native source code remains licensed under GPL-3.0 as
provided in the repository root `LICENSE` file.

如果你不能或不愿满足上述署名展示条件，则这些 NeriPlayer 自有 native
源码仍按根目录 `LICENSE` 中的 GPL-3.0 协议授权。

## No Warranty / 无担保

This native source code is provided "as is", without any express or
implied warranty. You are responsible for the risk of using, modifying,
integrating, or distributing it.

这些 native 源码按“原样”提供，不附带任何明示或默示担保。使用、修改、
集成或分发这些源码所产生的风险由使用者自行承担。
