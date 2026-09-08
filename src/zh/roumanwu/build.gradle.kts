import io.github.keiyoushi.gradle.api.ContentWarning

plugins {
    alias(kei.plugins.extension)
}

keiyoushi {
    name = "Roumanwu"
    versionCode = 20
    contentWarning = ContentWarning.NSFW
    libVersion = "1.4"

    source {
        name = "肉漫屋"
        lang = "zh"

        // 默认域名，可在应用内设置中自定义 / 切换镜像
        // 最新地址查询: https://rou.pub/dizhi or https://rdz3.xyz/dizhi
        baseUrl = "https://rouman5.com"
    }
}
