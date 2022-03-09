# maimai-bot

一个基于 [mirai](https://github.com/mamoe/mirai) 和 [舞萌 DX 查分器](https://www.diving-fish.com/maimaidx/prober) 编写的 maimai DX QQ 机器人插件。

## 使用指南

[点我下载](https://github.com/xszqxszq/maimai-bot/releases/download/v1.0/maimai-bot-1.0.mirai.jar)

本插件开箱即用，只需和其他插件一样放入 [MCL](https://github.com/iTXTech/mcl-installer) 或其他版本的 Mirai 控制台 的 ```plugins``` 目录即可。

如果您尚不清楚 Mirai 如何安装，请阅读 [Mirai 官方教程](https://github.com/mamoe/mirai/blob/dev/docs/UserManual.md) ，在安装好控制台后再安装本插件使用。

## 支持的功能

* b40
* b50
* 查歌
* XXX 是什么歌
* 谱面详情（例：紫id11154）
* 随机歌曲（例：随个紫12+）
* 定数查歌

## 常见问题

#### 如何修改字体？

修改 ```config/xyz.xszq.maimai-bot/config.yml``` 中的 ```fontName```值即可。

#### 如何更新歌曲别名？

本项目内置的别名来自 [歌曲别名添加表](https://docs.qq.com/sheet/DWGNNYUdTT01PY2N1) ，如果需要更新，则需要切换到“现有歌曲别名表”并点击右上角，导出为→csv并保存到 ```config/xyz.xszq.maimai-bot/aliases.csv```