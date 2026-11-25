# 第三方素材说明

TapeTape v2 的代码、图片、音效和界面文字由 TapeTape 项目自己编写或制作，以 MIT 许可证发布（见 `LICENSE`），以下素材除外。

| 素材 | 位置 | 来源 | 许可证 |
|---|---|---|---|
| Material Design 图标 | `android/app/src/main/res/drawable/ic_*.xml`、`msg_encr_out.xml`、`attach_*.xml`；`ios/TapeTape/Assets.xcassets/*.imageset/*.svg` | Google Material Design Icons（经 npm 包 `@material-design-icons/svg` 0.14.13 取得） | Apache License 2.0 |
| BIP-39 英文助记词表 | `ios/TapeTape/Resources/bip39-english.txt` | [bitcoin/bips](https://github.com/bitcoin/bips/blob/master/bip-0039/english.txt) | BIP-39 公开标准词表 |
| 表情符号列表 | `ios/TapeTape/Resources/emoji.txt` | Unicode 表情符号数据 | Unicode License v3 |

图片编辑器的贴纸用的是手机系统自带的表情字体，应用里不包含贴纸图片。

Rust、Android、iOS 的依赖库通过各自的包管理器（Cargo、Gradle、Swift Package Manager）引入，许可证以各自的包信息为准。目前的依赖都和 MIT 兼容：Rust 依赖为 MIT、Apache-2.0、BSD、ISC、MPL-2.0、Unicode 等许可证，Android 依赖为 Apache-2.0，iOS 没有第三方库。
