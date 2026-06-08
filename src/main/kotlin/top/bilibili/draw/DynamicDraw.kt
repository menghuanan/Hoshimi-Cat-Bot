package top.bilibili.draw

import org.jetbrains.skia.*
import org.jetbrains.skia.paragraph.Alignment
import org.jetbrains.skia.paragraph.ParagraphStyle
import org.jetbrains.skia.paragraph.TextStyle
import top.bilibili.core.BiliBiliBot
import top.bilibili.BiliConfigManager
import top.bilibili.data.*
import top.bilibili.data.DynamicType.DYNAMIC_TYPE_FORWARD
import top.bilibili.data.DynamicType.DYNAMIC_TYPE_NONE
import top.bilibili.draw.Position.*
import top.bilibili.tasker.DynamicMessageTasker.isUnlocked
import top.bilibili.service.DrawCacheKeyService
import top.bilibili.utils.*
import top.bilibili.skia.DrawingSession
import top.bilibili.skia.DrawingQueueManager
import top.bilibili.skia.SkiaManager


val logger by BiliBiliBot::logger

/**
 * 解析当前图片质量配置；返回副本是为了避免 badge 开关污染内置质量档位。
 */
private fun resolveImageQuality(): Quality {
    val imageConfig = BiliConfigManager.config.imageConfig
    val resolvedQuality = if (BiliImageQuality.customOverload) {
        logger.warn("图片分辨率配置已重载")
        BiliImageQuality.customQuality
    } else {
        var configuredQuality = BiliImageQuality.quality[imageConfig.quality]
        if (configuredQuality == null) {
            logger.error("未找到 ${imageConfig.quality} 的图片分辨率配置")
            configuredQuality = BiliImageQuality.quality.firstNotNullOf { it.value }
        }
        configuredQuality
    }
    return resolvedQuality.copy(
        badgeHeight = if (imageConfig.badgeEnable.enable) resolvedQuality.badgeHeight else 0,
    )
}

/**
 * 当前绘图质量必须跟随运行态配置读取，避免热重载后继续使用旧 lazy 值。
 */
val quality: Quality
    get() = resolveImageQuality()

/**
 * 解析当前图片主题配置，允许 WebUI 热重载或自定义主题 reload 后立即生效。
 */
private fun resolveImageTheme(): Theme {
    val imageConfig = BiliConfigManager.config.imageConfig
    return if (BiliImageTheme.customOverload) {
        logger.warn("图片主题配置已重载")
        BiliImageTheme.customTheme
    } else {
        var configuredTheme = BiliImageTheme.theme[imageConfig.theme]
        if (configuredTheme == null) {
            logger.error("未找到 ${imageConfig.theme} 的图片主题配置")
            configuredTheme = BiliImageTheme.theme.firstNotNullOf { it.value }
        }
        configuredTheme
    }
}

/**
 * 当前绘图主题必须跟随运行态配置读取，避免热重载后继续使用旧 lazy 值。
 */
val theme: Theme
    get() = resolveImageTheme()

val cardRect: Rect
    get() = Rect.makeLTRB(quality.cardMargin.toFloat(), 0f, quality.imageWidth - quality.cardMargin.toFloat(), 0f)

val cardContentRect: Rect
    get() = cardRect.inflate(-1f * quality.cardPadding)

val mainTypeface: Typeface
    get() = FontManager.mainTypeface

val font: Font
    get() = FontManager.font

val emojiTypeface: Typeface?
    get() = FontManager.emojiTypeface

val emojiFont: Font
    get() = FontManager.emojiFont

val fansCardFont: Font?
    get() = FontManager.fansCardFont

val titleTextStyle: TextStyle
    get() = DynamicDrawRuntimeStyles.current.titleTextStyle

val bigTitleTextStyle: TextStyle
    get() = DynamicDrawRuntimeStyles.current.bigTitleTextStyle

val descTextStyle: TextStyle
    get() = DynamicDrawRuntimeStyles.current.descTextStyle

val contentTextStyle: TextStyle
    get() = DynamicDrawRuntimeStyles.current.contentTextStyle

val footerTextStyle: TextStyle
    get() = DynamicDrawRuntimeStyles.current.footerTextStyle

val footerParagraphStyle: ParagraphStyle
    get() = DynamicDrawRuntimeStyles.current.footerParagraphStyle

/**
 * DynamicDraw 的全局排版样式是 Skiko Managed 对象，必须可刷新并在热重载或停机时统一关闭。
 */
internal object DynamicDrawRuntimeStyles : AutoCloseable {
    private val lock = Any()
    private var cached: RuntimeStyles? = null

    val current: RuntimeStyles
        get() = synchronized(lock) {
            cached ?: RuntimeStyles.create().also { cached = it }
    }

    /**
     * 图片质量、主题或页脚模板热重载后丢弃旧样式；调用方必须在绘图暂停窗口内执行。
     */
    fun reloadRuntimeConfig() {
        synchronized(lock) {
            cached?.close()
            cached = null
        }
    }

    /**
     * SkiaManager 停机时释放全局样式缓存，避免 TextStyle/ParagraphStyle native 资源泄漏。
     */
    override fun close() {
        synchronized(lock) {
            cached?.close()
            cached = null
        }
    }
}

/**
 * 一组运行态样式绑定同一轮质量、主题和页脚对齐配置，供绘图热路径复用。
 */
internal data class RuntimeStyles(
    val titleTextStyle: TextStyle,
    val bigTitleTextStyle: TextStyle,
    val descTextStyle: TextStyle,
    val contentTextStyle: TextStyle,
    val footerTextStyle: TextStyle,
    val footerParagraphStyle: ParagraphStyle,
) : AutoCloseable {
    companion object {
        /**
         * 创建样式快照时读取一次当前配置，保证同一组 paragraph/text 样式内部尺寸和颜色一致。
         */
        fun create(): RuntimeStyles {
            val runtimeQuality = quality
            val runtimeTheme = theme
            val familyName = mainTypeface.familyName
            val footerStyle = TextStyle().apply {
                fontSize = runtimeQuality.footerFontSize
                color = runtimeTheme.footerColor
                fontFamilies = arrayOf(familyName)
            }
            return RuntimeStyles(
                titleTextStyle = TextStyle().apply {
                    fontSize = runtimeQuality.titleFontSize
                    color = runtimeTheme.titleColor
                    fontFamilies = arrayOf(familyName)
                },
                bigTitleTextStyle = TextStyle().apply {
                    fontSize = runtimeQuality.titleFontSize + 3
                    color = runtimeTheme.titleColor
                    fontStyle = FontStyle.BOLD
                    fontFamilies = arrayOf(familyName)
                },
                descTextStyle = TextStyle().apply {
                    fontSize = runtimeQuality.descFontSize
                    color = runtimeTheme.descColor
                    fontFamilies = arrayOf(familyName)
                },
                contentTextStyle = TextStyle().apply {
                    fontSize = runtimeQuality.contentFontSize
                    color = runtimeTheme.contentColor
                    fontFamilies = arrayOf(familyName)
                },
                footerTextStyle = footerStyle,
                footerParagraphStyle = ParagraphStyle().apply {
                    maxLinesCount = 2
                    ellipsis = "..."
                    alignment = footerAlignment()
                    textStyle = footerStyle
                },
            )
        }
    }

    /**
     * ParagraphStyle 持有 footer TextStyle 引用，关闭时先关 paragraph 再释放各个 text style。
     */
    override fun close() {
        footerParagraphStyle.close()
        titleTextStyle.close()
        bigTitleTextStyle.close()
        descTextStyle.close()
        contentTextStyle.close()
        footerTextStyle.close()
    }
}

/**
 * 页脚对齐是绘图派生样式，必须基于最新运行态配置即时解析。
 */
private fun footerAlignment(): Alignment {
    return when (BiliConfigManager.config.templateConfig.footer.footerAlign.uppercase()) {
        "LEFT" -> Alignment.LEFT
        "CENTER" -> Alignment.CENTER
        "RIGHT" -> Alignment.RIGHT
        else -> Alignment.LEFT
    }
}

/**
 * 测试与调试入口只暴露当前页脚对齐名称，不让调用方持有 ParagraphStyle 实例。
 */
internal fun footerAlignmentName(): String = footerAlignment().name

val cardBadgeArc: FloatArray
    get() {
        val imageConfig = BiliConfigManager.config.imageConfig
        val left = if (imageConfig.badgeEnable.left) 0f else quality.cardArc
        val right = if (imageConfig.badgeEnable.right) 0f else quality.cardArc
        return floatArrayOf(left, right, quality.cardArc, quality.cardArc)
    }


/**
 * 根据主题色列表生成动态卡片图片并缓存到磁盘。
 *
 * @param colors 主题色列表
 * @param subject 可选的联系人标识
 * @param color 原始颜色字符串
 */
suspend fun DynamicItem.makeDrawDynamic(colors: List<Int>, subject: String? = null, color: String? = null): String {
    return makeDrawDynamic(colors.first(), generateLinearGradient(colors), subject, color)
}

/**
 * 根据主色和背景渐变生成动态卡片图片并缓存到磁盘。
 *
 * @param themeColor 主色
 * @param backgroundColors 背景渐变颜色
 * @param subject 可选的联系人标识
 * @param color 原始颜色字符串
 */
suspend fun DynamicItem.makeDrawDynamic(themeColor: Int, backgroundColors: IntArray, subject: String? = null, color: String? = null): String {
    return SkiaManager.executeDrawing {
        val dynamic = this@makeDrawDynamic.drawDynamic(this, themeColor, false)
        val img = makeCardBg(this, dynamic.height, backgroundColors) {
            it.drawImage(dynamic, 0f, 0f)
        }
        cacheImage(img, color?.let { DrawCacheKeyService.dynamicPath(mid, idStr ?: "0", subject, it) } ?: "$mid/$idStr.png", CacheType.DRAW_DYNAMIC)
        // All resources automatically released when session closes
    }
}

/**
 * 将单条动态绘制为完整卡片图像。
 *
 * @param session 当前绘制会话
 * @param themeColor 主色
 * @param isForward 是否按转发态样式绘制
 */
suspend fun DynamicItem.drawDynamic(session: DrawingSession, themeColor: Int, isForward: Boolean = false): Image {
    val orig = orig?.drawDynamic(session, themeColor, type == DYNAMIC_TYPE_FORWARD)

    var imgList = modules.makeGeneral(session, displayTime, link, type, themeColor, isForward, isUnlocked())

    // 调整附加卡片顺序
    if (orig != null) {
        // 转发动态需要把原动态主体插到附加卡片之前，保持阅读顺序与客户端一致。
        imgList = if (this.modules.moduleDynamic.additional != null) {
            val result = ArrayList<Image>(imgList.size + 1)
            result.addAll(imgList.subList(0, imgList.size - 1))
            result.add(orig)
            result.add(imgList.last())
            result
        } else {
            imgList.plus(orig)
        }
    }

    var plusHeight = 0
    if (type == DynamicType.DYNAMIC_TYPE_WORD || type == DYNAMIC_TYPE_NONE) {
        plusHeight += quality.contentSpace * 2
    }

    val footer = if (!isForward) {
        buildFooter(modules.moduleAuthor.name, modules.moduleAuthor.mid, did, displayTime, type.text)
    } else null

    // assembleCard 会关闭 imgList 中的所有 Image
    return with(session) {
        imgList.assembleCard(session, did, footer, plusHeight, isForward, closeInputImages = true).track()
    }

}

/**
 * 根据模板生成动态页脚文案。
 */
fun buildFooter(name: String, uid: Long, id: String, time: String, type: String): String? {
    val footerTemplate = BiliConfigManager.config.templateConfig.footer.dynamicFooter
    return if (footerTemplate.isNotBlank()) {
        footerTemplate
            .replace("{name}", name)
            .replace("{uid}", uid.toString())
            .replace("{id}", id)
            .replace("{time}", time)
            .replace("{type}", type)
    } else null
}

/**
 * 将多个 Image 组装成一张卡片
 * @param session DrawingSession for resource management
 * @param id 动态 ID
 * @param footer 页脚文本
 * @param plusHeight 额外高度
 * @param isForward 是否为转发动态
 * @param tag 标签
 * @param closeInputImages 是否在组装完成后关闭输入的 Image 列表，默认为 false
 * @return 组装后的 Image
 */
fun List<Image>.assembleCard(session: DrawingSession, id: String, footer: String? = null, plusHeight: Int = 0, isForward: Boolean = false, tag: String? = null, closeInputImages: Boolean = false): Image {
    val imageConfig = BiliConfigManager.config.imageConfig

    // 过滤无效的 Image，使用安全访问方法
    val validImages = this.filter { it.isValid() && it.safeWidth() > 0 && it.safeHeight() > 0 }
    if (validImages.isEmpty()) {
        logger.warn("assembleCard: 所有输入图片都无效，创建空白占位图")
        return getImageMiss(session)
    }

    val height = validImages.sumOf {
        val w = it.safeWidth()
        val h = it.safeHeight()
        if (w > cardRect.width) {
            (cardRect.width * h / w + quality.contentSpace).toInt()
        } else {
            h + quality.contentSpace
        }
    } + plusHeight

    val footerParagraph = if (footer != null) {
        with(session) {
            buildParagraph(footerParagraphStyle, FontUtils.fonts, cardRect.width) {
                addText(footer)
            }.track()
        }
    } else null

    val margin = if (isForward) quality.cardPadding * 2 else quality.cardMargin * 2
    val imgList = validImages  // 使用过滤后的有效图片列表

    return try {
        val surface = session.createSurface(
            (cardRect.width + margin).toInt(),
            height + quality.badgeHeight + margin + (footerParagraph?.height?.toInt() ?: 0)
        )
        val canvas = surface.canvas

        val rrect = RRect.makeComplexXYWH(
            margin / 2f,
            quality.badgeHeight + margin / 2f,
            cardRect.width,
            height.toFloat(),
            cardBadgeArc
        )

        if (isForward) {
            canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.smallCardShadow)
        } else {
            canvas.drawRectShadowAntiAlias(rrect.inflate(1f), theme.cardShadow)
        }

        if (imageConfig.badgeEnable.left) {
            val svg = session.createSvg("icon/${if (isForward) "FORWARD" else "BILIBILI_LOGO"}.svg")
            val badgeImage = svg?.makeImage(session, quality.contentFontSize, quality.contentFontSize)
            canvas.drawBadge(
                session,
                tag ?: if (isForward) "转发动态" else "动态",
                font,
                theme.mainLeftBadge.fontColor,
                theme.mainLeftBadge.bgColor,
                rrect,
                TOP_LEFT,
                badgeImage
            )
        }
        if (imageConfig.badgeEnable.right) {
            canvas.drawBadge(session, id, font, theme.mainRightBadge.fontColor, theme.mainRightBadge.bgColor, rrect, TOP_RIGHT)
        }

        canvas.drawCard(session, rrect)

        var top = quality.cardMargin + quality.badgeHeight.toFloat()
        for (img in imgList) {
            val imgWidth = img.safeWidth()
            val imgHeight = img.safeHeight()
            if (imgWidth <= 0 || imgHeight <= 0) continue  // 跳过无效图片

            canvas.drawScaleWidthImage(img, cardRect.width, quality.cardMargin.toFloat(), top)

            top += if (imgWidth > cardRect.width) {
                (cardRect.width * imgHeight / imgWidth + quality.contentSpace).toInt()
            } else {
                imgHeight + quality.contentSpace
            }
        }

        footerParagraph?.paint(canvas, cardRect.left, rrect.bottom + quality.cardMargin / 2)

        with(session) {
            surface.makeImageSnapshot().track()
        }
    } finally {
        // 如果需要关闭输入的 Image 列表
        if (closeInputImages) {
            // 组装阶段常会持有临时图片对象，显式收口可避免长链路累计原生资源。
            imgList.forEach { runCatching { it.close() } }
        }
    }
}

/**
 * 生成动态主体各模块对应的图片列表。
 */
suspend fun DynamicItem.Modules.makeGeneral(
    session: DrawingSession,
    time: String,
    link: String,
    type: DynamicType,
    themeColor: Int,
    isForward: Boolean = false,
    isUnlocked: Boolean = false
): List<Image> {
    return mutableListOf<Image>().apply {
        if (type != DYNAMIC_TYPE_NONE)
            add(if (isForward) moduleAuthor.drawForward(session, time) else moduleAuthor.drawGeneral(session, time, link, themeColor))
        if(isUnlocked){
            add(drawBlockedDefault(session))
        }else{
            moduleDispute?.drawGeneral(session)?.let { add(it) }
            addAll(moduleDynamic.makeGeneral(session, isForward))
        }
    }
}

/**
 * 绘制专属动态的默认占位图。
 */
fun drawBlockedDefault(session: DrawingSession): Image {
    val bgImg = with(session) {
        Image.makeFromEncoded(loadResourceBytes("image/Blocked_BG_Day.png")).track()
    }
    val bgWidth = cardContentRect.width - 2 * quality.cardPadding
    val bgHeight = bgImg.height.toFloat() / bgImg.width.toFloat() * bgWidth

    val textStyle = ParagraphStyle().apply {
        maxLinesCount = 2
        ellipsis = "..."
        alignment = Alignment.CENTER
        textStyle = titleTextStyle.apply {
            color = Color.WHITE
        }
    }
    val text = with(session) {
        buildParagraph(textStyle, FontUtils.fonts, bgWidth) {
            addText("此动态为专属动态\n请自行查看详情内容")
        }.track()
    }

    val surface = session.createSurface(
        cardContentRect.width.toInt(), (bgHeight + 3.0f * quality.cardPadding).toInt()
    )
    val canvas = surface.canvas

    val x = quality.cardPadding.toFloat()
    var y = quality.cardPadding.toFloat()
    canvas.drawImageClip(session, bgImg, RRect.Companion.makeXYWH(x, y, bgWidth, bgHeight, quality.cardArc))

    y += (bgHeight - text.height) / 2.0f
    text.paint(canvas, x, y)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

/**
 * 计算文本在矩形中的垂直基线位置。
 */
fun Rect.textVertical(text: TextLine) =
    bottom - (height - text.capHeight) / 2

/**
 * 计算标签卡片文字的垂直基线位置。
 */
internal fun labelCardTextBaseline(rrect: RRect, textLine: TextLine): Float =
    Rect.makeXYWH(rrect.left, rrect.top, rrect.width, rrect.height).textVertical(textLine)

/**
 * 绘制通用卡片背景与描边。
 */
fun Canvas.drawCard(session: DrawingSession, rrect: RRect, bgColor: Int = theme.cardBgColor) {
    val fillPaint = session.createPaint {
        color = bgColor
        mode = PaintMode.FILL
        isAntiAlias = true
    }
    drawRRect(rrect, fillPaint)

    val strokePaint = session.createPaint {
        color = theme.cardOutlineColors.first()
        mode = PaintMode.STROKE
        strokeWidth = quality.cardOutlineWidth
        isAntiAlias = true
    }
    strokePaint.shader = session.createSweepGradient(
        rrect.left + rrect.width / 2,
        rrect.top + rrect.height / 2,
        theme.cardOutlineColors
    )
    drawRRect(rrect, strokePaint)
}

/**
 * 使用颜色列表生成卡片背景图。
 */
fun makeCardBg(session: DrawingSession, height: Int, colors: List<Int>, block: (Canvas) -> Unit): Image {
    return makeCardBg(session, height, generateLinearGradient(colors), block)
}

/**
 * 使用渐变颜色数组生成卡片背景图。
 */
fun makeCardBg(session: DrawingSession, height: Int, gradientColors: IntArray, block: (Canvas) -> Unit): Image {
    val imageRect = Rect.makeXYWH(0f, 0f, quality.imageWidth.toFloat(), height.toFloat())
    val surface = session.createSurface(imageRect.width.toInt(), height)
    val canvas = surface.canvas

    val paint = session.createPaint {
        shader = session.createLinearGradient(
            Point(imageRect.left, imageRect.top),
            Point(imageRect.right, imageRect.bottom),
            gradientColors
        )
    }
    canvas.drawRect(imageRect, paint)
    block(canvas)

    return with(session) {
        surface.makeImageSnapshot().track()
    }
}

/**
 * 绘制头像、挂件和认证标识。
 */
suspend fun Canvas.drawAvatar(
    session: DrawingSession,
    face: String,
    pendant: String?,
    verifyType: Int?,
    faceSize: Float,
    verifyIconSize: Float,
    isForward: Boolean = false
) {

    val faceImg = getOrDownloadImage(face, CacheType.USER)

    val hasPendant = pendant != null && pendant != ""

    var tarFaceRect = RRect.makeXYWH(
        quality.cardPadding * if (isForward) 1.5f else 1.8f,
        quality.cardPadding * if (isForward) 1f else 1.2f,
        faceSize,
        faceSize,
        faceSize / 2
    )
    if (!hasPendant) {
        tarFaceRect = tarFaceRect.inflate(quality.noPendantFaceInflate) as RRect
        drawCircle(
            tarFaceRect.left + tarFaceRect.width / 2,
            tarFaceRect.top + tarFaceRect.width / 2,
            tarFaceRect.width / 2 + quality.noPendantFaceInflate / 2,
            session.createPaint { color = theme.faceOutlineColor })
    }

    faceImg?.let {
        try {
            drawImageRRect(it, tarFaceRect)
        } finally {
            it.close()
        }
    }

    if (hasPendant) {
        getOrDownloadImage(pendant!!, CacheType.USER)?.let { pendantImg ->
            try {
                val srcPendantRect = Rect(0f, 0f, pendantImg.width.toFloat(), pendantImg.height.toFloat())
                val tarPendantRect = Rect.makeXYWH(
                    tarFaceRect.left + tarFaceRect.width / 2 - quality.pendantSize / 2,
                    tarFaceRect.top + tarFaceRect.height / 2 - quality.pendantSize / 2,
                    quality.pendantSize, quality.pendantSize
                )
                drawImageRect(
                    pendantImg,
                    srcPendantRect,
                    tarPendantRect,
                    FilterMipmap(FilterMode.LINEAR, MipmapMode.NEAREST),
                    null,
                    true
                )
            } finally {
                pendantImg.close()
            }
        }
    }

    val verifyIcon = when (verifyType) {
        0 -> "PERSONAL_OFFICIAL_VERIFY"
        1 -> "ORGANIZATION_OFFICIAL_VERIFY"
        else -> ""
    }

    if (verifyIcon != "") {
        val svg = session.createSvg("icon/$verifyIcon.svg")
        if (svg != null) {
            val size = if (hasPendant) verifyIconSize - quality.noPendantFaceInflate / 2 else verifyIconSize
            val verifyImg = svg.makeImage(session, size, size)
            drawImage(
                verifyImg,
                tarFaceRect.right - size,
                tarFaceRect.bottom - size
            )
        }
    }

}

/**
 * 绘制卡片角标。
 */
fun Canvas.drawBadge(
    session: DrawingSession,
    text: String,
    font: Font,
    fontColor: Int,
    bgColor: Int,
    cardRect: Rect,
    position: Position,
    icon: Image? = null
) {
    val textLine = session.createTextLine(text, font)
    val badgeWidth = textLine.width + quality.badgePadding * 8 + (icon?.width ?: 0)

    val rrect = when (position) {
        TOP_LEFT -> RRect.makeXYWH(
            cardRect.left, cardRect.top - quality.badgeHeight, badgeWidth,
            quality.badgeHeight.toFloat(), quality.badgeArc, quality.badgeArc, 0f, 0f
        )

        TOP_RIGHT -> RRect.makeXYWH(
            cardRect.right - badgeWidth, cardRect.top - quality.badgeHeight, badgeWidth,
            quality.badgeHeight.toFloat(), quality.badgeArc, quality.badgeArc, 0f, 0f
        )

        BOTTOM_LEFT -> RRect.makeXYWH(
            cardRect.left, cardRect.bottom + quality.badgeHeight, badgeWidth,
            quality.badgeHeight.toFloat(), 0f, 0f, quality.badgeArc, quality.badgeArc
        )

        BOTTOM_RIGHT -> RRect.makeXYWH(
            cardRect.right - badgeWidth, cardRect.bottom + quality.badgeHeight, badgeWidth,
            quality.badgeHeight.toFloat(), 0f, 0f, quality.badgeArc, quality.badgeArc
        )
    }

    drawRectShadowAntiAlias(rrect.inflate(1f), theme.smallCardShadow)
    drawCard(session, rrect, bgColor)

    var x = rrect.left + quality.badgePadding * 4
    if (icon != null) {
        x -= quality.badgePadding
        drawImage(icon, x, rrect.top + (quality.badgeHeight - icon.height) / 2)
        x += icon.width + quality.badgePadding * 2
    }

    drawTextLine(
        textLine,
        x,
        rrect.bottom - (quality.badgeHeight - textLine.capHeight) / 2,
        session.createPaint { color = fontColor }
    )
}

/**
 * 绘制轻量标签卡片。
 */
fun Canvas.drawLabelCard(
    textLine: TextLine,
    x: Float,
    y: Float,
    fontPaint: Paint,
    bgPaint: Paint
) {

    val rrect = RRect.makeXYWH(
        x,
        y,
        textLine.width + quality.badgePadding * 4,
        textLine.height,
        quality.badgeArc
    )
    drawRRect(rrect, bgPaint)

    drawTextLine(
        textLine,
        rrect.left + quality.badgePadding * 2,
        labelCardTextBaseline(rrect, textLine),
        fontPaint
    )
}
