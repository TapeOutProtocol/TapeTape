import XCTest

final class M4Tour: XCTestCase {
    let app = XCUIApplication()
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-m4-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
    }

    func toRoot() {
        for _ in 0..<5 where !app.tabBars.buttons["Chats"].isHittable { back() }
        app.tabBars.buttons["Chats"].tap()
        for _ in 0..<5 where !app.buttons["Switch Profile"].firstMatch.waitForExistence(timeout: 1) { back() }
    }

    func switchTo(_ name: String) {
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["profile-\(name)"].tap()
        sleep(2)
    }

    func openChat(_ name: String) {
        let row = app.staticTexts[name].firstMatch
        XCTAssert(row.waitForExistence(timeout: 30), "chat \(name) missing")
        row.tap()
        sleep(2)
    }

    func row(_ prefix: String) -> XCUIElement {
        let el = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        XCTAssert(el.waitForExistence(timeout: 5), "row \(prefix) missing")
        return el
    }

    func attach(_ item: String) {
        app.buttons["Add Attachment"].tap()
        sleep(1)
        app.buttons[item].firstMatch.tap()
        sleep(2)
    }

    func dismissAlert() {
        app.alerts.buttons.firstMatch.tap()
        sleep(1)
    }

    func test1Gallery() {
        app.launchArguments += ["-TapeTapePacketGallery"]
        app.launch()
        XCTAssert(app.staticTexts["Packet Gallery"].waitForExistence(timeout: 10))
        shot("01-bubbles-red")
        app.swipeUp()
        shot("02-bubbles-transfer")
        app.swipeDown()
        app.descendants(matching: .any)["red-packet-9001"].firstMatch.tap()
        XCTAssert(app.buttons["open-red-packet"].waitForExistence(timeout: 5))
        shot("03-red-claim")
        app.buttons["open-red-packet"].tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 30))
        shot("04-red-claim-error")
        dismissAlert()
        app.buttons["Close"].firstMatch.tap()
        sleep(1)
        app.descendants(matching: .any)["red-packet-9002"].firstMatch.tap()
        sleep(2)
        shot("05-red-detail-claimed")
        back()
        app.descendants(matching: .any)["red-packet-9005"].firstMatch.tap()
        sleep(2)
        shot("06-red-detail-expired-refund")
        app.buttons["Refund"].firstMatch.tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 5))
        shot("07-refund-confirm")
        app.alerts.buttons["Cancel"].tap()
        back()
        app.swipeUp()
        app.descendants(matching: .any)["transfer-9101"].firstMatch.tap()
        XCTAssert(app.buttons["claim-transfer"].waitForExistence(timeout: 5))
        shot("08-transfer-claim")
        app.buttons["Back"].firstMatch.tap()
        sleep(1)
        app.descendants(matching: .any)["transfer-9102"].firstMatch.tap()
        sleep(1)
        shot("09-transfer-status-sent")
        app.buttons["Back"].firstMatch.tap()
        sleep(1)
        app.descendants(matching: .any)["transfer-9103"].firstMatch.tap()
        sleep(1)
        shot("10-transfer-status-claimed")
        app.buttons["Back"].firstMatch.tap()
        sleep(1)
        app.descendants(matching: .any)["transfer-9104"].firstMatch.tap()
        sleep(1)
        shot("11-transfer-status-expired")
        app.buttons["Back"].firstMatch.tap()
    }

    func test2Wallet() {
        app.launch()
        switchTo("Alice")
        app.tabBars.buttons["Settings"].tap()
        sleep(6)
        shot("12-settings-wallet")
        row("Receive").tap()
        sleep(1)
        shot("13-receive")
        app.buttons["Copy"].tap()
        shot("14-receive-copied")
        back()
        row("Withdraw").tap()
        sleep(3)
        shot("15-withdraw-empty")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Token'")).firstMatch.tap()
        sleep(1)
        shot("16-withdraw-token-sheet")
        app.sheets.buttons.matching(NSPredicate(format: "label BEGINSWITH 'USDT'")).firstMatch.tap()
        let address = app.textViews["withdraw-address"].exists ? app.textViews["withdraw-address"] : app.textFields["withdraw-address"]
        address.tap()
        address.typeText("0x000000000000000000000000000000000000dEaD")
        app.textFields["withdraw-amount"].tap()
        app.textFields["withdraw-amount"].typeText("1.5")
        sleep(4)
        shot("17-withdraw-filled")
        app.buttons["Confirm Withdraw"].tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 15))
        shot("18-withdraw-insufficient")
        dismissAlert()
    }

    func test3Packets() {
        app.launch()
        switchTo("Alice")
        openChat("Relay Trio 2")
        app.buttons["Add Attachment"].tap()
        sleep(1)
        shot("19-attach-group")
        app.buttons["Send Red Packet"].firstMatch.tap()
        sleep(3)
        shot("20-send-red-packet")
        app.textFields["packet-count"].tap()
        app.textFields["packet-count"].typeText("3")
        app.textFields["packet-amount"].tap()
        app.textFields["packet-amount"].typeText("1.5")
        shot("21-red-packet-filled")
        app.buttons["packet-send"].tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 15))
        shot("22-red-packet-insufficient")
        dismissAlert()
        app.buttons["Cancel"].firstMatch.tap()
        sleep(1)
        toRoot()
        openChat("Bob")
        app.buttons["Add Attachment"].tap()
        sleep(1)
        shot("23-attach-1to1")
        app.buttons["Send Transfer"].firstMatch.tap()
        sleep(3)
        shot("24-send-transfer")
        app.textFields["packet-amount"].tap()
        app.textFields["packet-amount"].typeText("2")
        app.buttons["packet-send"].tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 15))
        shot("25-transfer-insufficient")
        dismissAlert()
        app.buttons["Cancel"].firstMatch.tap()
    }

    func test4Shop() {
        app.launch()
        switchTo("Alice")
        openChat("Bob")
        app.buttons["Add Attachment"].tap()
        sleep(1)
        app.buttons["Sell"].firstMatch.tap()
        shot("26-shop-loading")
        XCTAssert(app.alerts["Open On-chain Shop"].waitForExistence(timeout: 30))
        shot("27-shop-open-dialog")
        app.alerts.buttons["Cancel"].tap()
    }
}
