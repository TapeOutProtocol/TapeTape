import XCTest

final class M5Robust: XCTestCase {
    let app = XCUIApplication()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-m5-robust-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    func allowNotifications() {
        for label in ["Allow", "允许"] {
            let allow = springboard.buttons[label]
            if allow.waitForExistence(timeout: 2) { allow.tap() }
        }
    }

    func send(_ text: String) {
        let input = app.textViews.firstMatch
        input.tap()
        input.typeText(text)
        app.buttons["Send"].tap()
        sleep(2)
    }

    func testUpgradeAndLifecycle() {
        app.launch()
        allowNotifications()
        sleep(2)
        for _ in 0..<3 where !app.tabBars.firstMatch.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            sleep(1)
        }
        app.tabBars.buttons["Settings"].tap()
        XCTAssert(app.staticTexts["Upgrader"].waitForExistence(timeout: 10), "M1 profile missing after upgrade")
        shot("01-upgraded-settings")

        app.tabBars.buttons["Contact"].tap()
        let me = app.staticTexts["Me"].firstMatch
        XCTAssert(me.waitForExistence(timeout: 10))
        me.tap()
        sleep(2)
        send("After upgrade")
        sleep(4)
        shot("02-upgraded-chat")

        XCUIDevice.shared.press(.home)
        sleep(8)
        app.activate()
        XCTAssert(app.textViews.firstMatch.waitForExistence(timeout: 10), "chat not shown after foreground")
        send("After foreground")
        sleep(4)
        shot("03-after-foreground")

        app.terminate()
        app.launch()
        XCTAssert(app.textViews.firstMatch.waitForExistence(timeout: 15), "last chat not restored after kill")
        XCTAssert(app.staticTexts["After foreground"].exists)
        shot("04-after-relaunch")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        app.swipeUp()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Advanced'")).firstMatch.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Proxy'")).firstMatch.tap()
        app.buttons["Add Proxy"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.typeText("socks5://127.0.0.1:9")
        app.alerts.buttons["Use Proxy"].tap()
        sleep(6)
        shot("05-offline-proxy")
        for _ in 0..<2 { app.navigationBars.buttons.element(boundBy: 0).tap(); sleep(1) }
        app.tabBars.buttons["Contact"].tap()
        app.staticTexts["Me"].firstMatch.tap()
        sleep(2)
        send("Sent while offline")
        sleep(3)
        shot("06-offline-pending")

        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.tabBars.buttons["Chats"].tap()
        app.buttons["Proxy"].firstMatch.tap()
        sleep(1)
        app.switches["Use Proxy"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        sleep(10)
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.tabBars.buttons["Contact"].tap()
        app.staticTexts["Me"].firstMatch.tap()
        sleep(3)
        shot("07-online-delivered")
    }

    func testTypeAfterContactOpen() {
        app.launch()
        sleep(2)
        for _ in 0..<3 where !app.tabBars.firstMatch.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            sleep(1)
        }
        app.tabBars.buttons["Contact"].tap()
        let me = app.staticTexts["Me"].firstMatch
        XCTAssert(me.waitForExistence(timeout: 10))
        me.tap()
        XCUIDevice.shared.press(.home)
        sleep(3)
        app.terminate()
        app.launch()
        XCTAssert(app.textViews.firstMatch.waitForExistence(timeout: 15), "not restored")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        app.tabBars.buttons["Contact"].tap()
        app.staticTexts["Me"].firstMatch.tap()
        sleep(2)
        send("Typed after contact open")
        sleep(2)
        XCTAssert(app.staticTexts["Typed after contact open"].exists, "message split")
        shot("90-contact-open-typing")
    }

    func toChats() {
        for _ in 0..<4 where !app.tabBars.firstMatch.exists {
            app.navigationBars.buttons.element(boundBy: 0).tap()
            sleep(1)
        }
        app.tabBars.buttons["Chats"].tap()
        sleep(1)
    }

    func switchTo(_ name: String) {
        toChats()
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["profile-\(name)"].tap()
        sleep(3)
    }

    func testOfflineOnline() {
        app.launch()
        sleep(2)
        switchTo("Alice")
        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        app.swipeUp()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Advanced'")).firstMatch.tap()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Proxy'")).firstMatch.tap()
        app.buttons["Add Proxy"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.typeText("socks5://127.0.0.1:9")
        app.alerts.buttons["Use Proxy"].tap()
        sleep(5)
        toChats()
        shot("10-offline-chatlist")
        app.staticTexts["Bob"].firstMatch.tap()
        sleep(2)
        send("Queued while offline")
        sleep(5)
        shot("11-offline-queued")
        toChats()
        app.buttons["Proxy"].firstMatch.tap()
        sleep(1)
        app.switches["Use Proxy"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        sleep(3)
        let saved = app.buttons.matching(NSPredicate(format: "label BEGINSWITH '127.0.0.1'")).firstMatch
        saved.swipeLeft()
        sleep(1)
        app.buttons["Delete"].firstMatch.tap()
        if app.sheets.buttons["Delete Proxy"].waitForExistence(timeout: 5) { app.sheets.buttons["Delete Proxy"].tap() }
        sleep(1)
        toChats()
        app.staticTexts["Bob"].firstMatch.tap()
        sleep(15)
        shot("12-online-sent")
        switchTo("Bob")
        app.staticTexts["Alice"].firstMatch.tap()
        XCTAssert(app.staticTexts["Queued while offline"].waitForExistence(timeout: 60), "queued message not delivered after reconnect")
        shot("13-bob-received")
        toChats()
    }
}
