import XCTest

final class M5Tour: XCTestCase {
    let app = XCUIApplication()
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()

    override func setUp() {
        continueAfterFailure = true
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-m5-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    func tab(_ i: Int) {
        app.tabBars.buttons.element(boundBy: i).tap()
        sleep(2)
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
    }

    func label(_ en: String, _ zh: String) -> XCUIElement {
        app.buttons.element(matching: NSPredicate(format: "label IN %@", [en, zh])).firstMatch
    }

    func toRoot() {
        for _ in 0..<5 where !app.tabBars.firstMatch.isHittable { back() }
        tab(0)
        for _ in 0..<5 where !label("Switch Profile", "切换账号").waitForExistence(timeout: 1) { back() }
    }

    func openChat(_ name: String) {
        let row = app.staticTexts[name].firstMatch
        XCTAssert(row.waitForExistence(timeout: 30), "chat \(name) missing")
        row.tap()
        sleep(2)
    }

    func attachMenu() {
        label("Add Attachment", "添加附件").tap()
        sleep(1)
    }

    func tour(_ lang: String) {
        app.launchArguments = ["-AppleLanguages", "(\(lang))", "-AppleLocale", lang == "en" ? "en_US" : "zh_CN"]
        app.launch()
        sleep(3)
        toRoot()
        label("Switch Profile", "切换账号").tap()
        app.buttons["profile-Alice"].tap()
        sleep(3)
        shot("\(lang)-01-chat-list")
        if lang == "en" {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
            sleep(1)
            app.searchFields.firstMatch.tap()
            app.typeText("Androidzzqx")
            sleep(2)
            shot("en-08b-chatlist-search-empty")
            app.buttons["Close"].firstMatch.exists ? app.buttons["Close"].firstMatch.tap() : app.buttons["Cancel"].firstMatch.tap()
            sleep(1)
        }
        openChat("Bob")
        shot("\(lang)-11-chat-1to1")
        attachMenu()
        shot("\(lang)-12-attach-menu-1to1")
        label("Send Transfer", "发送转账").tap()
        sleep(3)
        shot("\(lang)-16-send-transfer")
        label("Cancel", "取消").tap()
        sleep(1)
        let bubble = app.staticTexts["Ping for notification"].firstMatch
        if bubble.waitForExistence(timeout: 5) {
            bubble.press(forDuration: 1.0)
            shot("\(lang)-21-message-context-menu")
            label("Copy", "复制").tap()
            sleep(1)
        }
        toRoot()
        openChat("Relay Trio 2")
        shot("\(lang)-24-chat-group")
        if lang == "en" {
            app.navigationBars.staticTexts["Relay Trio 2"].firstMatch.tap()
            sleep(2)
            app.buttons["Edit"].firstMatch.tap()
            sleep(2)
            shot("en-23b-edit-group")
            app.buttons["Cancel"].firstMatch.tap()
            sleep(1)
            app.buttons["Add Members"].firstMatch.tap()
            sleep(2)
            shot("en-23d-add-members")
            app.buttons["Cancel"].firstMatch.tap()
            sleep(1)
            back()
        }
        attachMenu()
        label("Send Red Packet", "发红包").tap()
        sleep(3)
        shot("\(lang)-18-send-red-packet")
        label("Cancel", "取消").tap()
        sleep(1)
        back()
        if lang == "en" {
            app.navigationBars.staticTexts.element(boundBy: 0).tap()
            sleep(2)
            shot("en-61-connectivity")
            back()
        }
        tab(1)
        shot("\(lang)-20-contacts-tab")
        tab(3)
        sleep(3)
        shot("\(lang)-50-settings")
        app.swipeUp()
        shot("\(lang)-51-settings-bottom")
        if lang == "en" {
            app.swipeDown()
            app.buttons.element(matching: NSPredicate(format: "label BEGINSWITH 'Chats and Media'")).tap()
            sleep(1)
            app.buttons.element(matching: NSPredicate(format: "label BEGINSWITH 'Delete Old Messages'")).tap()
            sleep(1)
            shot("en-53d-delete-old")
            back()
            back()
            app.swipeUp()
        }
        let advanced = app.buttons.element(matching: NSPredicate(format: "label BEGINSWITH 'Advanced' OR label BEGINSWITH '高级'"))
        if advanced.exists {
            advanced.tap()
            sleep(1)
            shot("\(lang)-56-advanced")
            back()
        }
        let invite = app.buttons.element(matching: NSPredicate(format: "label BEGINSWITH 'Invite Friends' OR label BEGINSWITH '邀请好友'"))
        if invite.exists {
            invite.tap()
            sleep(2)
            shot("\(lang)-59-invite-friends")
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
            sleep(1)
        }
        tab(0)
    }

    func test1English() { tour("en") }
    func test2Chinese() { tour("zh-Hans") }
}
