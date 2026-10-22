# 音之理音 · 官方宣传页

纯静态单页，**无需任何构建工具**：没有 npm、没有打包器、没有外部 CDN，
全部资源都是相对路径，直接丢到任意虚拟主机就能打开。

```
site/
├── index.html              主页（含三语文案的默认中文）
├── 404.html                四零四页（支持自定义错误页的主机可自动使用）
├── .htaccess               可选：Apache 主机的首页/压缩/缓存规则
├── assets/
│   ├── style.css           Material 3 设计系统 + 响应式 + 深色模式
│   ├── app.js              加载动画、滚动进场、水波纹、语言切换
│   ├── i18n.js             中 / EN / 日本語 三语字典
│   ├── logo.svg            品牌图标（也用作 OGI 图与触屏图标）
│   └── favicon.svg         站点图标
└── download/
    └── yzliyin-2.0.37257.apk   安装包（21 MB，来自 app/build/outputs/apk/release/）
```

## 部署到虚拟主机

1. 用 FTP / 主机面板的文件管理器，把 **`site/` 目录里的所有内容**（不是 `site/` 本身）
   上传到站点根目录，即 `index.html` 要和 `public_html` / `wwwroot` / `htdocs` 同级。
2. 保持目录结构不变：`assets/` 和 `download/` 必须是 `index.html` 的子目录。
3. 浏览器打开域名即可。`download/` 里的 APK 不上传的话，「下载 APK」按钮会 404，
   但页面其它部分不受影响。

要点：

- **全部是相对路径**，无论挂在域名根目录还是子目录（`https://xxx.com/yzliyin/`）都能正常工作。
- **不依赖任何外部网络**（无谷歌字体、无 CDN），国内外访问一致，断网也能打开。
- 有 `index.html` 即可，多数主机默认文档已配置；若 404，把默认首页设为 `index.html`。
- `.htaccess` 是可选增强（gzip、缓存、默认文档）。主机若是 Nginx 会自动忽略它；
  若上传后出现 500 错误，删掉 `.htaccess` 即可，不影响页面。

## 语言

默认简体中文，首次访问按浏览器语言自动选中 中文 / English / 日本語，
用户也可在顶栏地球图标里手动切换，选择会记在 `localStorage`。
三语词条由脚本按 `data-i18n` 键替换，词条与 `index.html` 中的默认中文严格一致
（校验脚本可比对）。

## 更新 APK

```bash
# 1) 构建新版
./gradlew assembleRelease
# 2) 覆盖站点里的安装包，并同步改 index.html / i18n.js 中的版本号与体积
cp app/build/outputs/apk/release/app-release.apk \
   site/download/yzliyin-<新版本号>.apk
```

按钮上标注的版本号与文件名要一起改（`index.html` 中 `href="download/..."` 与
`<small class="btn__note">` 两处，`footer__links` 中一处）。

`download/*.apk` 已在仓库的 `.gitignore` 中忽略，避免把二进制安装包塞进 Git 历史；
上传虚拟主机时仍需手动带上这个文件。
