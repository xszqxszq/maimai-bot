# maimaiBot

一个基于 [mirai](https://github.com/mamoe/mirai) 和 [舞萌 DX 查分器](https://www.diving-fish.com/maimaidx/prober) 编写的 maimai DX QQ 机器人插件。

## 支持的功能

* b40
* b50
* 查歌
* XXX 是什么歌
* 谱面详情（例：紫id11154）
* 歌曲分数信息（例：info 834）
* 随机歌曲（例：随个紫12+）
* 别名查询（例：11154有什么别名）
* 定数查歌
* 分数线
* 猜歌
* 随机推分金曲（例：mai什么加2分；随机推分金曲）
* 随机推分列表
* 牌子进度（例：晓极进度）
* 牌子完成表（例：橙将完成表）
* 等级进度（例：13ss+进度，11ap进度，10fdx进度，14clear进度）
* 等级完成表（例：13+完成表）
* 等级分数列表（例：14分数列表）


## 部署指南

[点我下载](https://github.com/xszqxszq/maimai-bot/releases/download/v1.3.1/maimai-bot-1.3.1.mirai.jar)

本插件开箱即用，只需和其他插件一样放入 [MCL](https://github.com/iTXTech/mcl-installer) 或其他版本的 Mirai 控制台 的 ```plugins``` 目录即可。

如果您尚不清楚 Mirai 如何安装，请阅读 [Mirai 官方教程](https://github.com/mamoe/mirai/blob/dev/docs/UserManual.md) ，在安装好控制台后再安装本插件使用。

## 常见问题

#### 插件指令完全没反应？

注意！！**请不要给 `*:*` 权限**，这会导致插件误认为该群禁用了所有功能。请删除 `*:*`。

#### 如何在特定群关闭本插件功能？

在控制台输入 `/perm grant g114514 maimaiBot:denyall`，其中 114514 请替换成需要关闭插件的群号。

#### 如何修改字体？

修改 ```config/xyz.xszq.maimai-bot/主题名/theme.yml``` 中的 ```fontName```值即可。

#### 如何更新歌曲别名？

对于更新了新版本的用户：删除 `config/xyz.xszq.maimai-bot/aliases.csv` 并重启即可更新成新版本内置的别名表。

本项目内置的别名来自 [歌曲别名添加表](https://docs.qq.com/sheet/DWGNNYUdTT01PY2N1) 和 [歌曲别名表](https://docs.qq.com/sheet/DVnJUb0pYeXJxakVk) ，如果需要更新，则需要切换到“现有歌曲别名表”并点击右上角，导出为→csv并保存到 ```config/xyz.xszq.maimai-bot/aliases.csv```

#### 查不到任何歌曲谱面信息？

请检查 [舞萌 DX 查分器](https://www.diving-fish.com/maimaidx/prober) 是否可以访问，同时请检查您的网络连接是否畅通，是否开启了无效的代理设置等。

#### 更新插件后部分内容没更新或者有误？

请关闭 bot 后删除 ```config/xyz.xszq.maimai-bot``` 文件夹和 ```data/xyz.xszq.maimai-bot``` 文件夹。


## 配置文件

```config/xyz.xszq.maimai-bot/settings.yml``` 为本插件的主配置文件，默认情况下无需修改。

配置各项说明如下：
* `theme: portrait` 设置要使用的 b40 / b50 主题名称。`config/xyz.xszq.maimai-bot` 下的各文件夹为主题文件夹，文件夹名即主题名。插件自带的 *portrait* 主题为竖式 b40 / b50 ，*classical* 为经典的横式 b40 / b50。
* `multiAccountsMode: false` 开启多账号登录支持。开启后如果 bot 多个号在一个群内时，对同一请求不会处理两次。
* `coverSource: WAHLAP` 封面下载源。如果无法下载，请尝试改成 `ZETARAKU`。
* `maidataJsonUrls:` 封面下载源为 `WAHLAP` 时，包含图片文件名信息的 `maidata.json` 的下载地址。您可以根据网络情况更换为能访问的 GitHub Raw 镜像网址。
* `zetarakuSite:` `ZETARAKU` 下载源服务器地址。如果无法下载，请访问 [maimai-songs](https://maimai-songs.zetaraku.dev/) 并用开发者工具获取新的服务器地址。 