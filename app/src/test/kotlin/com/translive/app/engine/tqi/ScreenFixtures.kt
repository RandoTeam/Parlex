package com.translive.app.engine.tqi

import android.graphics.Rect
import com.translive.app.service.accessibility.ExtractedTextNode

object ScreenFixtures {

    private fun rect(left: Int, top: Int, right: Int, bottom: Int) = Rect().apply {
        this.left = left
        this.top = top
        this.right = right
        this.bottom = bottom
    }

    /**
     * Archetype 1: Firefox / DuckDuckGo Search Results.
     * Contains giant GeckoView root container, multiple distinct search result cards,
     * breadcrumbs, and Czech search settings footer.
     */
    val ARCHETYPE_WEB_SEARCH = ScreenFixture(
        id = "archetype_web_search",
        name = "Web Search & Cards (Firefox / DuckDuckGo)",
        screenWidth = 1440,
        screenHeight = 3168,
        rawNodes = listOf(
            ExtractedTextNode(
                id = "gecko_doc_root",
                text = "Android Police: Android news, reviews, guides, editorials... Android | Android Police What's an Android site... Nastavení vyhledávání",
                bounds = rect(0, 200, 1440, 2800),
                className = "org.mozilla.geckoview.GeckoView"
            ),
            ExtractedTextNode(
                id = "card1_title",
                text = "Android Police: Android news, reviews, guides, editorials, and...",
                bounds = rect(48, 412, 1392, 492)
            ),
            ExtractedTextNode(
                id = "card1_desc",
                text = "Google built a security cage for AI agents on Android, but it's still empty Galaxy Z Fold 8 Ultra Vs. Honor Magic V6 is complicated. [Video]",
                bounds = rect(48, 510, 1392, 750)
            ),
            ExtractedTextNode(
                id = "card2_title",
                text = "Android | Android Police",
                bounds = rect(48, 810, 1392, 890)
            ),
            ExtractedTextNode(
                id = "card2_desc",
                text = "What's an Android site without extensive Android coverage? You can find everything Android Police publishes on Android right here.",
                bounds = rect(48, 908, 1392, 1150)
            ),
            ExtractedTextNode(
                id = "card3_title",
                text = "Android Police - YouTube",
                bounds = rect(48, 1210, 1392, 1290)
            ),
            ExtractedTextNode(
                id = "card3_url",
                text = "https://www.youtube.com › @AndroidPolice",
                bounds = rect(48, 1300, 1392, 1350)
            ),
            ExtractedTextNode(
                id = "search_footer",
                text = "Nastavení vyhledávání",
                bounds = rect(48, 2600, 1392, 2800)
            )
        ),
        expectedPreservedIds = setOf("card1_title", "card1_desc", "card2_title", "card2_desc", "card3_title", "search_footer"),
        expectedNoiseIds = setOf("gecko_doc_root", "card3_url"),
        protectedEntities = listOf("Android Police", "Android", "Google", "YouTube")
    )

    /**
     * Archetype 2: Messenger / Chat Dialog (Telegram / WhatsApp).
     * Alternating left and right bubbles, message timestamps, reply quotes.
     */
    val ARCHETYPE_MESSENGER_CHAT = ScreenFixture(
        id = "archetype_messenger_chat",
        name = "Messenger & Chat Dialog (Telegram)",
        screenWidth = 1440,
        screenHeight = 3168,
        rawNodes = listOf(
            ExtractedTextNode(
                id = "top_clock",
                text = "14:05",
                bounds = rect(48, 20, 200, 70)
            ),
            ExtractedTextNode(
                id = "top_battery",
                text = "79%",
                bounds = rect(1250, 20, 1420, 70)
            ),
            ExtractedTextNode(
                id = "incoming_bubble",
                text = "Hello! Have you tested the new Snapdragon 8 Elite NPU offline translation models?",
                bounds = rect(36, 400, 1050, 620)
            ),
            ExtractedTextNode(
                id = "incoming_time",
                text = "14:05",
                bounds = rect(950, 580, 1040, 615)
            ),
            ExtractedTextNode(
                id = "outgoing_quote",
                text = "Snapdragon 8 Elite NPU offline translation",
                bounds = rect(450, 700, 1400, 770)
            ),
            ExtractedTextNode(
                id = "outgoing_bubble",
                text = "Yes, Parlex runs fully on-device with zero server latency and sub-40ms Skia inpainting.",
                bounds = rect(400, 780, 1404, 980)
            ),
            ExtractedTextNode(
                id = "outgoing_time",
                text = "14:07",
                bounds = rect(1310, 940, 1394, 975)
            )
        ),
        expectedPreservedIds = setOf("incoming_bubble", "outgoing_quote", "outgoing_bubble"),
        expectedNoiseIds = setOf("top_clock", "top_battery", "incoming_time", "outgoing_time"),
        protectedEntities = listOf("Snapdragon", "Parlex", "Skia")
    )

    /**
     * Archetype 3: Technical Documentation & Articles (Obsidian / Markdown Reader).
     * Bold H1/H2 headings, multi-paragraph markdown body, technical brand entities.
     */
    val ARCHETYPE_DOCUMENTATION = ScreenFixture(
        id = "archetype_documentation",
        name = "Technical Documentation (Obsidian Markdown)",
        screenWidth = 1440,
        screenHeight = 3168,
        rawNodes = listOf(
            ExtractedTextNode(
                id = "doc_h1",
                text = "In-Place Augmented Reality Translation Engine",
                bounds = rect(64, 300, 1376, 380)
            ),
            ExtractedTextNode(
                id = "doc_p1",
                text = "Parlex renders translated text spans strictly anchored to the original left edge. BrandEntityShield protects technical trademarks and libraries from machine translation calques.",
                bounds = rect(64, 420, 1376, 780)
            ),
            ExtractedTextNode(
                id = "doc_h2",
                text = "Zero-Allocation Skia Inpainting",
                bounds = rect(64, 840, 1376, 910)
            ),
            ExtractedTextNode(
                id = "doc_p2",
                text = "During onDraw, the view samples dominant edge colors and executes direct round-rect erasing before drawing StaticLayout text blocks.",
                bounds = rect(64, 950, 1376, 1280)
            )
        ),
        expectedPreservedIds = setOf("doc_h1", "doc_p1", "doc_h2", "doc_p2"),
        expectedNoiseIds = emptySet(),
        protectedEntities = listOf("Parlex", "BrandEntityShield", "Skia", "StaticLayout")
    )

    /**
     * Archetype 4: App Store & Settings Grid (Google Play).
     * Multi-column card layout, prices, installation buttons, status bars.
     */
    val ARCHETYPE_APP_STORE_GRID = ScreenFixture(
        id = "archetype_app_store_grid",
        name = "App Store Grid & Settings (Google Play)",
        screenWidth = 1440,
        screenHeight = 3168,
        rawNodes = listOf(
            ExtractedTextNode(
                id = "grid_clock",
                text = "14:05",
                bounds = rect(48, 20, 200, 70)
            ),
            ExtractedTextNode(
                id = "app1_title",
                text = "PocketPal AI Studio",
                bounds = rect(48, 500, 680, 560)
            ),
            ExtractedTextNode(
                id = "app1_price",
                text = "$4.99",
                bounds = rect(48, 570, 250, 630)
            ),
            ExtractedTextNode(
                id = "app2_title",
                text = "DeepMind AlphaCode",
                bounds = rect(740, 500, 1392, 560)
            ),
            ExtractedTextNode(
                id = "app2_price",
                text = "$0.00",
                bounds = rect(740, 570, 950, 630)
            ),
            ExtractedTextNode(
                id = "app_category",
                text = "Recommended Productivity Tools",
                bounds = rect(48, 380, 1392, 450)
            )
        ),
        expectedPreservedIds = setOf("app_category", "app1_title", "app2_title"),
        expectedNoiseIds = setOf("grid_clock", "app1_price", "app2_price"),
        protectedEntities = listOf("PocketPal AI", "DeepMind"),
        isMultiColumn = true
    )

    val ALL_ARCHETYPES = listOf(
        ARCHETYPE_WEB_SEARCH,
        ARCHETYPE_MESSENGER_CHAT,
        ARCHETYPE_DOCUMENTATION,
        ARCHETYPE_APP_STORE_GRID
    )
}
