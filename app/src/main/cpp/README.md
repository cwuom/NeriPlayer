# NeriPlayer Native Source License / Native 源码授权说明

Last updated / 最后更新：2026-07-21

This directory contains NeriPlayer native source code, and also includes
third-party native source code used by the build.

本目录包含 NeriPlayer 的 native 源码，也包含随项目构建使用的第三方
native 源码。

## Scope / 授权范围

This extra license applies only to NeriPlayer-owned portions of the current
native source and native-test paths listed below. Future files under these
paths are covered only when the NeriPlayer project author owns the copyright
or otherwise has the right to offer this alternative license.

下面的额外授权仅适用于下列路径中由 NeriPlayer 项目拥有版权的 native
源码和 native 测试文件。以后新增到这些路径的文件，也只有在 NeriPlayer
项目作者拥有版权或有权提供本替代授权时，才会被纳入：

- `crash/`
- `usb/exclusive/`
- `usb/feedback/`
- `usb/iso/`
- `usb/pcm/`
- `usb/uac1/`
- `usb/uac2/`
- `tests/usb/`, for NeriPlayer-owned host tests and supporting test files
- `tests/usb/`，仅限 NeriPlayer 自有的 host 测试及其配套测试文件
- NeriPlayer-owned portions of `CMakeLists.txt` and `tests/usb/CMakeLists.txt`
- `CMakeLists.txt` 和 `tests/usb/CMakeLists.txt` 中由 NeriPlayer 自有的部分

This alternative license does not automatically apply to Kotlin, Java,
Android resources, scripts or tools outside this directory, generated build
outputs, or any other repository content not included in the scope above.

本替代授权不会自动扩展到本目录之外的 Kotlin、Java、Android 资源、脚本、
工具、生成的构建产物，或其他未列入上述范围的仓库内容。

This license does not apply to third-party source code with its own
copyright or license notice.

它不适用于带有独立版权或独立许可证声明的第三方源码，例如：

- `libusb/` keeps following `LGPL-2.1-or-later` as declared in its file headers
- `libusb/` 源码继续遵循文件头中声明的 `LGPL-2.1-or-later`
- Imported test fixtures, traces, corpora, or specifications with their own notices
- 带有独立声明的外部测试夹具、反馈轨迹、语料或规范资料
- Any other file with an explicit third-party copyright or license notice
- 其他明确标注了独立许可证或第三方版权信息的文件

If a file contains both NeriPlayer-owned code and third-party code, the
third-party parts still follow their original licenses. A file-specific
copyright or license notice takes precedence over the path list above.

如果某个文件同时包含 NeriPlayer 自有代码和第三方代码，第三方代码仍按其
原许可证处理。文件自身明确标注的版权或许可证声明优先于上述路径列表。

## External Contributions / 外部贡献

Native contributions from other developers are covered by this alternative
license only when the contributor explicitly grants both licenses for that
contribution:

其他开发者贡献的 native 源码，只有在贡献者明确同意时，才会被纳入本
替代授权。贡献者需要同意其贡献同时按以下两种方式授权：

- GPL-3.0, as provided in the repository root `LICENSE` file
- 根目录 `LICENSE` 中的 GPL-3.0 协议
- The NeriPlayer Native Attribution License described in this README
- 本 README 中描述的 NeriPlayer Native Attribution License / 署名授权

Submitting a pull request, patch, or commit by itself does not create this
alternative-license grant. The dual-license agreement must be recorded in the
pull request, commit, signed agreement, or another form accepted by the
copyright holder.

仅提交 pull request、patch 或 commit，本身不代表已经授予本替代授权。
双授权同意必须记录在 pull request、commit、签署的协议，或版权持有人接受的
其他可审计形式中。

Suggested contribution statement / 建议的贡献声明：

```text
I license my contribution under both GPL-3.0 and the NeriPlayer Native
Attribution License in app/src/main/cpp/README.md.

我同意将本次贡献同时按 GPL-3.0 和 app/src/main/cpp/README.md 中的
NeriPlayer Native Attribution License 授权。
```

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

For NeriPlayer-owned native source code included in the Scope above and not
excluded by a file-specific or third-party notice, the copyright holder grants
the following alternative license in addition to the GPL-3.0 license in the
repository root `LICENSE` file.

对于纳入上述授权范围、且未被文件自身声明或第三方声明排除的 NeriPlayer
自有 native 源码，除了根目录 `LICENSE` 中的 GPL-3.0 授权外，版权持有人
额外授予以下替代授权：

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
   在最终产品中用户容易看到的位置展示 NeriPlayer 项目信息
2. The information must include the project name, project link, and original author
   项目信息至少包含项目名称、项目链接和原作者信息
3. Do not imply that NeriPlayer or the original author endorses or certifies your project
   不得暗示 NeriPlayer 或原作者为你的项目提供背书、认证或担保
4. Do not remove existing copyright, license, or third-party notices from the source code
   不得移除源码中已有的版权、许可证或第三方声明

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
