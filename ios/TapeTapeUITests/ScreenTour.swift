import XCTest

final class ScreenTour: XCTestCase {
    let app = XCUIApplication()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()

    override func setUp() {
        continueAfterFailure = true
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    func launch(_ extra: [String]) {
        app.launchArguments = ["-TapeTapeReset", "-AppleLanguages", "(en)", "-AppleLocale", "en_US"] + extra
        app.launch()
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
    }

    func testOnboarding() {
        launch([])
        XCTAssert(app.buttons["Create New Profile"].waitForExistence(timeout: 10))
        shot("00a-welcome")
        app.buttons["I Already Have a Profile"].tap()
        shot("00b-welcome-have-profile")
        app.buttons["Import Mnemonic"].firstMatch.tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 5))
        shot("00c-import-mnemonic-dialog")
        app.alerts.textFields.firstMatch.typeText("abandon abandon wrong")
        app.alerts.buttons["Next"].tap()
        XCTAssert(app.alerts.buttons["OK"].waitForExistence(timeout: 5))
        shot("00c2-import-invalid")
        app.alerts.buttons["OK"].tap()

        app.buttons["Create New Profile"].tap()
        XCTAssert(app.staticTexts["Automatic"].waitForExistence(timeout: 10))
        shot("00d-profile-setup")
        app.buttons.matching(NSPredicate(format: "label CONTAINS 'Automatic'")).firstMatch.tap()
        shot("00e-node-menu")
        app.buttons["Automatic"].firstMatch.tap()
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText("RelayTester")
        app.buttons["Agree & Create Profile"].tap()
        let copy = app.alerts.buttons["Copy and Continue"]
        XCTAssert(copy.waitForExistence(timeout: 10))
        copy.tap()
        XCTAssert(app.staticTexts["Chats"].waitForExistence(timeout: 15))
        shot("01b-chat-list-new-profile")
    }

    func createProfile(_ name: String) {
        XCTAssert(app.buttons["Create New Profile"].waitForExistence(timeout: 15))
        app.buttons["Create New Profile"].tap()
        XCTAssert(app.staticTexts["Automatic"].waitForExistence(timeout: 30))
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText(name)
        app.buttons["Agree & Create Profile"].tap()
        let copy = app.alerts.buttons["Copy and Continue"]
        XCTAssert(copy.waitForExistence(timeout: 15))
        copy.tap()
        UIPasteboard.general.items = []
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.buttons["Agree & Create Profile"])
        wait(for: [gone], timeout: 120)
        XCTAssert(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        sleep(1)
    }

    func switchTo(_ name: String) {
        app.tabBars.buttons["Chats"].tap()
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["profile-\(name)"].tap()
        sleep(2)
    }

    func send(_ text: String) {
        let input = app.textViews.firstMatch.exists ? app.textViews.firstMatch : app.textFields.firstMatch
        input.tap()
        input.typeText(text)
        app.buttons["Send"].tap()
    }

    func testRealTwoProfiles() {
        app.resetAuthorizationStatus(for: .camera)
        launch([])
        createProfile("Alice")
        shot("01-real-chat-list-empty")

        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["Add Profile"].tap()
        XCTAssert(app.buttons["Create New Profile"].waitForExistence(timeout: 10))
        shot("00a-real-add-profile")
        createProfile("Bob")

        switchTo("Alice")
        app.tabBars.buttons["Contact"].tap()
        app.buttons["New Contact"].tap()
        sleep(1)
        shot("30-real-qr-invite")
        app.navigationBars.buttons["More"].tap()
        app.buttons["Copy to Clipboard"].tap()
        back()
        switchTo("Bob")

        app.tabBars.buttons["Contact"].tap()
        app.buttons["New Contact"].tap()
        app.navigationBars.buttons["More"].tap()
        app.buttons["Paste from Clipboard"].tap()
        let allow = springboard.buttons["Allow Paste"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 10))
        XCTAssert(app.alerts.staticTexts["Chat with Alice?"].exists)
        if app.alerts.staticTexts["Chat with Alice?"].exists { shot("34-real-invite-confirm") }
        app.alerts.buttons["OK"].tap()
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 60))
        sleep(1)
        XCTAssertFalse(app.tabBars.buttons["Contact"].isHittable)
        shot("35-real-invite-chat")
        send("Hello Alice, this is Bob")
        sleep(15)
        shot("11-real-bob-sent")
        back()
        sleep(1)
        shot("01-real-bob-chat-list")

        switchTo("Alice")
        XCTAssert(app.staticTexts["Bob"].waitForExistence(timeout: 60))
        sleep(3)
        shot("01-real-alice-chat-list")
        app.staticTexts["Bob"].tap()
        sleep(2)
        shot("11-real-alice-received")
        app.staticTexts["Hello Alice, this is Bob"].press(forDuration: 1.0)
        shot("21-real-message-context-menu")
        app.buttons["Reply"].firstMatch.tap()
        shot("11c-real-reply-draft")
        send("Hi Bob, got your message")
        sleep(15)
        shot("11-real-alice-replied")
        back()

        switchTo("Bob")
        XCTAssert(app.staticTexts["Alice"].waitForExistence(timeout: 30))
        sleep(5)
        app.staticTexts["Alice"].tap()
        sleep(3)
        shot("11-real-bob-received-reply")
        back()
        app.buttons["Switch Profile"].firstMatch.tap()
        shot("02-real-account-switcher")
        app.buttons["Close"].firstMatch.tap()
        sleep(1)
        app.buttons["New Chat"].firstMatch.tap()
        shot("03-real-new-chat")
        back()
        app.tabBars.buttons["Contact"].tap()
        sleep(1)
        shot("20-real-contacts-tab")
        app.buttons["New Contact"].tap()
        app.navigationBars.buttons["More"].tap()
        shot("31-real-qr-menu")
        app.buttons["Copy to Clipboard"].tap()
        app.buttons["Scan QR Code"].tap()
        let deny = springboard.buttons.matching(NSPredicate(format: "label IN {'Don’t Allow', '不允许'}")).firstMatch
        if deny.waitForExistence(timeout: 5) {
            shot("32-real-qr-scan")
            deny.tap()
        }
        if app.alerts.buttons["Cancel"].waitForExistence(timeout: 3) {
            shot("33-real-qr-scan-denied")
            app.alerts.buttons["Cancel"].tap()
        }
        back()
        sleep(1)
        app.staticTexts["Alice"].firstMatch.tap()
        sleep(2)
        shot("20b-real-contact-opens-chat")
        back()
        app.tabBars.buttons["Square"].tap()
        sleep(6)
        shot("40-real-square-tab")
        app.tabBars.buttons["Settings"].tap()
        shot("50-real-settings")
        app.staticTexts["Bob"].firstMatch.tap()
        shot("52-real-self-profile")
        back()
    }
}
