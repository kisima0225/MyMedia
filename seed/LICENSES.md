# 演示数据的许可证与署名

本仓库**不包含任何第三方媒体文件**。`scripts/fetch-seed.*` 按 `seed/manifest.json`
从下列来源获取，校验 SHA-256 后放进 `data/media/`（该目录被 `.gitignore` 里的
`media/` 规则忽略，媒体文件不会进入版本库）。

CC BY 要求署名，下表就是署名；公有领域作品本无需署名，仍然列出来源方便核实。

| 作品 | 作者 / 机构 | 许可证 | 来源 |
|---|---|---|---|
| Sintel（预告片） | Blender Foundation | CC BY 3.0 | https://download.blender.org/durian/trailer/sintel_trailer-480p.mp4 |
| Big Buck Bunny（预告片） | Blender Foundation | CC BY 3.0 | https://download.blender.org/peach/trailer/trailer_480p.mov |
| 神奈川冲浪里 | 葛饰北斋（1831 前后，Wikimedia 用户翻刻版） | 公有领域 | https://commons.wikimedia.org/wiki/File:The_Great_Wave_off_Kanagawa.jpg |
| 神奈川冲浪里（大都会艺术博物馆藏本，同一原作的另一次数字化） | 葛饰北斋（19 世纪） | 公有领域 | https://commons.wikimedia.org/wiki/File:Tsunami_by_hokusai_19th_century.jpg |
| 凯风快晴（赤富士，《富岳三十六景》之一） | 葛饰北斋 | 公有领域 | https://commons.wikimedia.org/wiki/File:Red_Fuji_southern_wind_clear_morning.jpg |
| 山下白雨（《富岳三十六景》之一） | 葛饰北斋 | 公有领域 | https://commons.wikimedia.org/wiki/File:Lightnings_below_the_summit.jpg |
| 甲州石班沢（《富岳三十六景》之一） | 葛饰北斋 | 公有领域 | https://commons.wikimedia.org/wiki/File:Kajikazawa_in_Kai_province.jpg |
| 駿州江尻（《富岳三十六景》之一） | 葛饰北斋 | 公有领域 | https://commons.wikimedia.org/wiki/File:Ejiri_in_the_Suruga_province.jpg |

> 后 6 项均属葛饰北斋《富岳三十六景》系列，取自 Wikimedia Commons，文件页均明确标注
> "This work is in the public domain"（許可證模板 `Public domain` / `Public domain`）。
> 其中前两项（神奈川冲浪里 / Tsunami）是**同一幅原作**的两次不同数字化翻拍——一份是
> Commons 用户自制的翻刻版，另一份出自大都会艺术博物馆——因此两张图会有肉眼可见的
> 色调差异，这是刻意保留的两份来源，不是重复上传的失误。

`scripts/generate-seed-offline.sh` 生成的占位媒体由 ffmpeg 的 `testsrc` 合成，
不含任何第三方素材，无许可证约束。
