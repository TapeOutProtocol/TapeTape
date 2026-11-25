import XCTest

final class M2Tour: XCTestCase {
    let app = XCUIApplication()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-\(name).png")
        try? XCUIScreen.main.screenshot().pngRepresentation.write(to: url)
    }

    func back() {
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)
    }

    func allowSystemAlert() {
        let allow = springboard.buttons.matching(NSPredicate(format: "label IN {'Allow', '允许', 'OK', '好', 'Allow Paste', '允许粘贴'}")).firstMatch
        if allow.waitForExistence(timeout: 4) { allow.tap() }
    }

    func switchTo(_ name: String) {
        app.tabBars.buttons["Chats"].tap()
        if !app.buttons["Switch Profile"].firstMatch.waitForExistence(timeout: 5) { back() }
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["profile-\(name)"].tap()
        sleep(2)
    }

    func openChat(_ name: String) {
        let row = app.staticTexts[name].firstMatch
        XCTAssert(row.waitForExistence(timeout: 30))
        row.tap()
        sleep(2)
    }

    func longPress(_ text: String) {
        let menu = app.buttons["More Options"]
        for _ in 0..<10 where menu.exists { sleep(1) }
        let all = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text))
        let el = all.count > 0 ? all.element(boundBy: all.count - 1) : all.firstMatch
        var tries = 0
        while !(el.waitForExistence(timeout: 2) && el.isHittable) && tries < 8 {
            app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.3)).press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)))
            tries += 1
        }
        XCTAssert(el.exists)
        for _ in 0..<3 {
            el.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1.2)
            if app.buttons["More Options"].waitForExistence(timeout: 2) { break }
            sleep(2)
        }
    }

    func swipeRow(_ name: String, left: Bool) {
        let cell = app.cells.containing(.staticText, identifier: name).firstMatch
        XCTAssert(cell.waitForExistence(timeout: 10))
        let a = cell.coordinate(withNormalizedOffset: CGVector(dx: left ? 0.85 : 0.15, dy: 0.5))
        let b = cell.coordinate(withNormalizedOffset: CGVector(dx: left ? 0.35 : 0.65, dy: 0.5))
        a.press(forDuration: 0.05, thenDragTo: b, withVelocity: .slow, thenHoldForDuration: 0.1)
        sleep(1)
    }

    func gradientImage(_ caption: String, _ a: UIColor, _ b: UIColor) -> UIImage {
        let size = CGSize(width: 800, height: 600)
        return UIGraphicsImageRenderer(size: size).image { ctx in
            let colors = [a.cgColor, b.cgColor] as CFArray
            let gradient = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(), colors: colors, locations: [0, 1])!
            ctx.cgContext.drawLinearGradient(gradient, start: .zero, end: CGPoint(x: 0, y: size.height), options: [])
            let attrs: [NSAttributedString.Key: Any] = [.font: UIFont.systemFont(ofSize: 72), .foregroundColor: UIColor.black]
            let text = caption as NSString
            let t = text.size(withAttributes: attrs)
            text.draw(at: CGPoint(x: (size.width - t.width) / 2, y: (size.height - t.height) / 2), withAttributes: attrs)
        }
    }

    func send(_ text: String) {
        let input = app.textViews.firstMatch
        input.tap()
        input.typeText(text)
        app.buttons["Send"].tap()
        sleep(2)
    }

    func sendFile() {
        app.buttons["Add Attachment"].tap()
        sleep(1)
        app.buttons["Files"].firstMatch.tap()
        sleep(2)
        let browse = app.buttons["Browse"].firstMatch
        if browse.waitForExistence(timeout: 3) { browse.tap(); sleep(1) }
        let local = app.staticTexts["On My iPhone"].firstMatch
        if local.waitForExistence(timeout: 3) { local.tap(); sleep(1) }
        let file = app.cells.containing(NSPredicate(format: "label BEGINSWITH 'tapetape-notes'")).firstMatch
        XCTAssert(file.waitForExistence(timeout: 5))
        file.tap()
        sleep(2)
        shot("11l-file-staged")
        app.buttons["Send"].tap()
        sleep(4)
        shot("11m-file-sent")
    }

    func restoreAlice() {
        let link = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Archived'")).firstMatch
        guard link.waitForExistence(timeout: 2) else { return }
        link.tap()
        sleep(1)
        swipeRow("Alice", left: true)
        app.buttons["Unarchive"].firstMatch.tap()
        sleep(1)
        back()
    }

    func testListSwipes() {
        switchTo("Bob")
        restoreAlice()
        swipeRow("Alice", left: true)
        shot("05-chatlist-swipe-left")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)).tap()
        sleep(1)
        swipeRow("Alice", left: false)
        shot("06-chatlist-swipe-right")
        app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.7)).tap()
        sleep(1)
        app.cells.containing(.staticText, identifier: "Alice").firstMatch.press(forDuration: 1.0)
        sleep(1)
        shot("07-chatlist-multiselect")
        app.buttons["Cancel"].firstMatch.tap()
    }

    func testReceive() {
        switchTo("Alice")
        openChat("Bob")
        sleep(3)
        shot("11k-alice-receives-media")
        let play = app.buttons.matching(identifier: "play.circle.fill").allElementsBoundByIndex.last
        play?.tap()
        sleep(2)
        shot("11o-alice-plays-voice")
        app.swipeDown()
        app.swipeDown()
        sleep(1)
        shot("11k2-alice-receives-media-top")
    }



    func testM2Chat() {
        switchTo("Bob")
        restoreAlice()
        openChat("Alice")
        send("Reaction test")
        send("Picker test")

        longPress("Reaction test")
        shot("21-message-context-menu")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH '👍'")).firstMatch.tap()
        sleep(3)

        longPress("Picker test")
        app.buttons["•••"].firstMatch.tap()
        XCTAssert(app.navigationBars["React"].waitForExistence(timeout: 5))
        shot("25-reaction-picker")
        app.buttons["😂"].firstMatch.tap()
        sleep(3)
        shot("11e-reactions")

        app.buttons.matching(identifier: "Reactions").allElementsBoundByIndex.last?.tap()
        sleep(1)
        shot("25b-reactions-list")
        app.navigationBars.buttons.element(boundBy: 0).tap()
        sleep(1)

        app.buttons["Add Attachment"].tap()
        sleep(1)
        shot("12-attach-menu-1to1")
        app.buttons["Contact"].firstMatch.tap()
        let alice = app.collectionViews.buttons.firstMatch
        XCTAssert(alice.waitForExistence(timeout: 5))
        alice.tap()
        sleep(1)
        shot("11f-vcard-staged")
        app.buttons["Send"].tap()
        sleep(3)

        UIPasteboard.general.image = gradientImage("iOS to Android", .systemBlue, .systemOrange)
        let input = app.textViews.firstMatch
        input.tap()
        input.press(forDuration: 1.2)
        let paste = app.menuItems["Paste"].firstMatch
        if paste.waitForExistence(timeout: 3) { paste.tap() }
        allowSystemAlert()
        sleep(2)
        shot("11g-image-staged")
        app.buttons["Send"].tap()
        sleep(4)
        shot("11h-image-sent")

        app.buttons["Voice Message"].firstMatch.tap()
        allowSystemAlert()
        sleep(4)
        shot("13-voice-recorder")
        app.buttons["Pause"].firstMatch.tap()
        sleep(1)
        shot("14-voice-recorder-paused")
        app.buttons["Send"].firstMatch.tap()
        sleep(2)
        shot("15-voice-effect")
        app.buttons["Robot"].firstMatch.tap()
        sleep(1)
        shot("15b-voice-effect-robot")
        app.buttons.matching(identifier: "Send").allElementsBoundByIndex.last?.tap()
        sleep(5)
        shot("11-chat-1to1")

        longPress("Reaction test")
        app.buttons["More Options"].firstMatch.tap()
        sleep(1)
        shot("26-chat-multiselect")
        app.buttons["More Options"].firstMatch.tap()
        sleep(1)
        shot("27-chat-multiselect-more")
        app.buttons["Resend"].firstMatch.tap()
        sleep(1)

        sendFile()

        longPress("Picker test")
        app.buttons["Forward"].firstMatch.tap()
        sleep(1)
        shot("03b-forward-to")
        app.staticTexts["Saved Messages"].firstMatch.tap()
        sleep(3)
        shot("11i-saved-messages")
        back()

        swipeRow("Alice", left: true)
        shot("05-chatlist-swipe-left")
        app.buttons["Archive"].firstMatch.tap()
        sleep(2)
        shot("01c-chat-list-archive-link")
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Archived'")).firstMatch.tap()
        sleep(1)
        shot("52-archive")
        swipeRow("Alice", left: true)
        app.buttons["Unarchive"].firstMatch.tap()
        sleep(1)
        back()

        swipeRow("Alice", left: false)
        shot("06-chatlist-swipe-right")
        app.buttons["Pin"].firstMatch.tap()
        sleep(1)
        swipeRow("Alice", left: true)
        app.buttons["Mute"].firstMatch.tap()
        let forever = app.buttons["Mute forever"].firstMatch
        if forever.waitForExistence(timeout: 3) { forever.tap() }
        sleep(1)
        shot("01d-chat-list-pinned-muted")

        app.cells.containing(.staticText, identifier: "Alice").firstMatch.press(forDuration: 1.0)
        sleep(1)
        shot("07-chatlist-multiselect")
        app.buttons["Cancel"].firstMatch.tap()
        sleep(1)

        swipeRow("Alice", left: false)
        app.buttons["Unpin"].firstMatch.tap()
        sleep(1)
        swipeRow("Alice", left: true)
        app.buttons["Unmute"].firstMatch.tap()
        sleep(1)

        app.swipeDown()
        let search = app.searchFields.firstMatch
        if search.waitForExistence(timeout: 3) {
            search.tap()
            search.typeText("Hello")
            sleep(2)
            shot("08-chatlist-search")
            let cancel = app.buttons.matching(NSPredicate(format: "label IN {'Cancel', 'Close'}")).firstMatch
            cancel.tap()
        }
    }

    func unblockAlice(_ name: String?) {
        app.tabBars.buttons["Settings"].tap()
        app.buttons["Chats and Media"].firstMatch.tap()
        app.buttons["Blocked Contacts"].firstMatch.tap()
        sleep(1)
        if let name { shot(name) }
        let alice = app.staticTexts["Alice"].firstMatch
        if alice.waitForExistence(timeout: 2) {
            alice.tap()
            app.buttons["Unblock Contact"].firstMatch.tap()
            sleep(2)
            if name != nil { shot("53b-blocked-empty") }
        }
        back()
        back()
        app.tabBars.buttons["Chats"].tap()
    }

    func testM2Details() {
        switchTo("Bob")
        unblockAlice(nil)
        openChat("Alice")
        app.buttons["View Profile"].firstMatch.tap()
        sleep(1)
        shot("22-contact-detail")
        app.swipeUp()
        shot("22b-contact-detail-bottom")
        app.buttons["Block Contact"].firstMatch.tap()
        sleep(1)
        shot("22f-block-confirm")
        app.alerts.buttons["Block Contact"].firstMatch.tap()
        sleep(2)
        shot("22g-after-block")
        app.tabBars.buttons["Contact"].tap()
        sleep(1)
        shot("40-contacts-after-block")
        unblockAlice("53a-blocked")
        openChat("Alice")
        app.buttons["View Profile"].firstMatch.tap()
        sleep(1)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Media'")).firstMatch.tap()
        sleep(1)
        shot("22d-all-media-gallery")
        app.buttons["Files"].firstMatch.tap()
        sleep(1)
        shot("22c-all-media-files")
        back()
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Disappearing Messages'")).firstMatch.tap()
        sleep(1)
        shot("22e-disappearing")
        app.buttons["After 1 minute"].firstMatch.tap()
        app.buttons["OK"].firstMatch.tap()
        sleep(3)
        shot("11n-disappearing-on")
        send("This disappears in a minute")
        app.buttons["View Profile"].firstMatch.tap()
        sleep(1)
        app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Disappearing Messages'")).firstMatch.tap()
        sleep(1)
        app.buttons["Off"].firstMatch.tap()
        app.buttons["OK"].firstMatch.tap()
        sleep(3)

        app.buttons["View Profile"].firstMatch.tap()
        sleep(1)
        app.buttons["Search in Chat"].firstMatch.tap()
        sleep(1)
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText("Alice")
        sleep(2)
        shot("11j-in-chat-search")
        app.buttons["Cancel"].firstMatch.tap()
        back()

        switchTo("Alice")
        openChat("Bob")
        sleep(3)
        shot("11k-alice-receives-media")
        app.swipeDown()
        shot("11k2-alice-receives-media-top")
    }
}
