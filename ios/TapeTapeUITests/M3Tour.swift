import XCTest

final class M3Tour: XCTestCase {
    let app = XCUIApplication()
    let springboard = XCUIApplication(bundleIdentifier: "com.apple.springboard")
    let dir = ProcessInfo.processInfo.environment["TAPETAPE_SHOTS"] ?? NSTemporaryDirectory()
    let group = "Relay Trio"

    override func setUp() {
        continueAfterFailure = true
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US"]
        app.launch()
        allowNotifications()
    }

    func allowNotifications() {
        let allow = springboard.buttons["Allow"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
    }

    func shot(_ name: String) {
        sleep(1)
        let url = URL(fileURLWithPath: dir).appendingPathComponent("v2-ios-m3-\(name).png")
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

    func hasProfile(_ name: String) -> Bool {
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        let exists = app.buttons["profile-\(name)"].waitForExistence(timeout: 3)
        app.navigationBars.buttons["Close"].firstMatch.tap()
        sleep(1)
        return exists
    }

    func openChat(_ name: String) {
        let row = app.staticTexts[name].firstMatch
        XCTAssert(row.waitForExistence(timeout: 60), "chat \(name) missing")
        row.tap()
        sleep(2)
    }

    func send(_ text: String) {
        let input = app.textViews.firstMatch
        input.tap()
        input.typeText(text)
        app.buttons["Send"].tap()
        sleep(2)
    }

    func longPress(_ text: String) {
        let all = app.staticTexts.matching(NSPredicate(format: "label CONTAINS %@", text))
        let el = all.count > 0 ? all.element(boundBy: all.count - 1) : all.firstMatch
        XCTAssert(el.waitForExistence(timeout: 30), "message \(text) missing")
        for _ in 0..<3 {
            el.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).press(forDuration: 1.2)
            if app.buttons["More Options"].waitForExistence(timeout: 2) { break }
            sleep(2)
        }
    }

    func swipeLeft(_ name: String) {
        let cell = app.cells.containing(.staticText, identifier: name).firstMatch
        XCTAssert(cell.waitForExistence(timeout: 10))
        let a = cell.coordinate(withNormalizedOffset: CGVector(dx: 0.85, dy: 0.5))
        let b = cell.coordinate(withNormalizedOffset: CGVector(dx: 0.35, dy: 0.5))
        a.press(forDuration: 0.05, thenDragTo: b, withVelocity: .slow, thenHoldForDuration: 0.1)
        sleep(1)
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
        let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.buttons["Agree & Create Profile"])
        wait(for: [gone], timeout: 120)
        XCTAssert(app.tabBars.firstMatch.waitForExistence(timeout: 10))
        sleep(1)
    }

    func copyInvite() {
        app.tabBars.buttons["Contact"].tap()
        app.buttons["New Contact"].tap()
        sleep(1)
        app.navigationBars.buttons["More"].tap()
        app.buttons["Copy to Clipboard"].tap()
        back()
    }

    func pasteInvite() {
        app.tabBars.buttons["Contact"].tap()
        app.buttons["New Contact"].tap()
        app.navigationBars.buttons["More"].tap()
        app.buttons["Paste from Clipboard"].tap()
        let allow = springboard.buttons["Allow Paste"]
        if allow.waitForExistence(timeout: 3) { allow.tap() }
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 10))
    }

    func openBackupPicker(shotName: String?) -> Int {
        sleep(2)
        let browse = app.buttons["Browse"].firstMatch
        if browse.waitForExistence(timeout: 3) { browse.tap(); sleep(1) }
        let local = app.staticTexts["On My iPhone"].firstMatch
        if local.waitForExistence(timeout: 3) { local.tap(); sleep(1) }
        let folders = app.cells.containing(NSPredicate(format: "label BEGINSWITH 'TapeTape'"))
        _ = folders.firstMatch.waitForExistence(timeout: 5)
        shot("51a-files-root")
        if let folder = folders.allElementsBoundByIndex.first(where: { $0.isHittable }) { folder.tap(); sleep(1) }
        let files = app.cells.matching(NSPredicate(format: "label BEGINSWITH 'tapetape-backup'"))
        _ = files.firstMatch.waitForExistence(timeout: 5)
        if let shotName { shot(shotName) }
        return files.count
    }

    func readPasteboard() -> String {
        var text = ""
        let done = expectation(description: "pasteboard")
        DispatchQueue.global().async {
            text = UIPasteboard.general.string ?? ""
            UIPasteboard.general.items = []
            done.fulfill()
        }
        let allow = springboard.buttons.matching(NSPredicate(format: "label IN {'Allow Paste', '允许粘贴'}")).firstMatch
        if allow.waitForExistence(timeout: 5) { allow.tap() }
        wait(for: [done], timeout: 10)
        return text
    }

    func enterPasscode() {
        for host in [springboard, XCUIApplication(bundleIdentifier: "com.apple.CoreAuthUI")] {
            let field = host.secureTextFields.firstMatch
            if field.waitForExistence(timeout: 3) {
                field.typeText("1234\n")
                sleep(2)
                return
            }
        }
    }

    func row(_ prefix: String) -> XCUIElement {
        let el = app.buttons.matching(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        XCTAssert(el.waitForExistence(timeout: 5), "row \(prefix) missing")
        return el
    }

    func test1Carol() {
        XCTAssert(hasProfile("Carol"))
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        sleep(1)
        shot("01-switcher")
        app.navigationBars.buttons["Close"].firstMatch.tap()
        switchTo("Alice")
        copyInvite()
        switchTo("Carol")
        pasteInvite()
        app.alerts.buttons["OK"].tap()
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 60))
        send("Hi Alice, Carol here")
        sleep(10)
    }

    func test2Group() {
        switchTo("Alice")
        let ready = app.staticTexts["Carol"].firstMatch
        XCTAssert(ready.waitForExistence(timeout: 60))
        app.tabBars.buttons["Contact"].tap()
        sleep(1)
        shot("02-contacts")
        app.buttons["New Group"].tap()
        sleep(1)
        shot("03-new-group-empty")
        app.textFields.firstMatch.tap()
        app.textFields.firstMatch.typeText(group)
        app.buttons["Add Members"].tap()
        XCTAssert(app.buttons.containing(NSPredicate(format: "label CONTAINS 'Bob'")).firstMatch.waitForExistence(timeout: 5))
        app.buttons.containing(NSPredicate(format: "label CONTAINS 'Bob'")).firstMatch.tap()
        app.buttons.containing(NSPredicate(format: "label CONTAINS 'Carol'")).firstMatch.tap()
        shot("04-add-members")
        app.buttons["Done"].tap()
        sleep(1)
        shot("05-new-group-filled")
        app.buttons["Create"].tap()
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 30))
        sleep(2)
        shot("06-group-created")
        send("Hello group, Alice here")
        send("Docs at https://tapetape.org today")
        sleep(5)
        app.navigationBars.staticTexts[group].firstMatch.tap()
        sleep(2)
        shot("07-group-details")
        app.buttons["QR Invite Code"].tap()
        sleep(2)
        shot("08-group-qr")
        back()
        app.buttons["Edit"].tap()
        let field = app.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.coordinate(withNormalizedOffset: CGVector(dx: 0.97, dy: 0.5)).tap()
        field.typeText(" 2")
        shot("09-edit-group")
        app.buttons["Save"].tap()
        sleep(3)
        back()
        sleep(2)
        shot("10-group-renamed")
        longPress("https://tapetape.org")
        shot("11-copy-link-menu")
        XCTAssert(app.buttons["Copy Link"].exists)
        app.buttons["Copy Link"].tap()
        sleep(1)
    }

    func test3GroupMembers() {
        switchTo("Bob")
        openChat("\(group) 2")
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 60))
        sleep(2)
        shot("12-bob-group")
        send("Bob checking in")
        longPress("Hello group, Alice here")
        shot("13-reply-privately-menu")
        app.buttons["Reply Privately"].tap()
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 10))
        sleep(1)
        send("Private answer from Bob")
        sleep(3)
        shot("14-private-reply")

        switchTo("Carol")
        openChat("\(group) 2")
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 60))
        sleep(3)
        shot("15-carol-group")
        app.navigationBars.staticTexts["\(group) 2"].firstMatch.tap()
        sleep(2)
        app.buttons["Leave Group"].tap()
        app.sheets.buttons["Leave Group"].tap()
        sleep(3)
        shot("16-carol-left-details")
        back()
        sleep(1)
        shot("17-carol-left-chat")

        switchTo("Alice")
        openChat("\(group) 2")
        sleep(5)
        shot("18-alice-group-events")
        app.navigationBars.staticTexts["\(group) 2"].firstMatch.tap()
        sleep(2)
        swipeLeft("Bob")
        shot("19-remove-member-swipe")
        app.buttons["Remove"].firstMatch.tap()
        XCTAssert(app.sheets.buttons["Remove"].waitForExistence(timeout: 5))
        shot("20-remove-member-confirm")
        app.sheets.buttons["Remove"].tap()
        sleep(3)
        shot("21-member-removed")
        app.buttons["QR Invite Code"].tap()
        sleep(1)
        app.navigationBars.buttons["More"].tap()
        app.buttons["Copy to Clipboard"].tap()
        back()
        app.buttons["Clone Chat"].tap()
        sleep(2)
        shot("22-clone-prefilled")
        back()
        back()
        sleep(2)
        shot("23-alice-group-after-remove")

        switchTo("Carol")
        pasteInvite()
        shot("24-join-group-confirm")
        XCTAssert(app.alerts.staticTexts["Do you want to join the group \"\(group) 2\"?"].exists)
        app.alerts.buttons["OK"].tap()
        XCTAssert(app.buttons["Send"].waitForExistence(timeout: 90))
        sleep(3)
        send("Carol is back")
        sleep(3)
        shot("25-carol-rejoined")
    }

    func test4ChatExtras() {
        switchTo("Alice")
        sleep(1)
        shot("26-chatlist-no-search")
        let start = app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.25))
        start.press(forDuration: 0.05, thenDragTo: app.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)))
        sleep(1)
        shot("27-chatlist-search-revealed")
        XCTAssert(app.searchFields.firstMatch.isHittable)
        openChat("Bob")
        app.navigationBars.staticTexts["Bob"].firstMatch.tap()
        sleep(1)
        app.buttons["Search in Chat"].tap()
        sleep(2)
        XCTAssert(app.keyboards.firstMatch.waitForExistence(timeout: 5))
        shot("28-chat-search-focused")
        app.typeText("Private")
        sleep(1)
        shot("29-chat-search-result")
        app.navigationBars.buttons["Cancel"].tap()
        sleep(1)
        app.navigationBars.staticTexts["Bob"].firstMatch.tap()
        sleep(2)
        app.swipeUp()
        sleep(1)
        shot("30-contact-shared-chats")
    }

    func test5Settings() {
        switchTo("Alice")
        app.tabBars.buttons["Settings"].tap()
        sleep(1)
        shot("31-settings")
        row("Chats and Media").tap()
        sleep(1)
        shot("32-chats-media")
        row("Outgoing Media Quality").tap()
        sleep(1)
        app.buttons["Worse quality, small size"].tap()
        shot("33-media-quality")
        app.buttons["Balanced"].tap()
        back()
        row("Auto-Download Messages").tap()
        sleep(1)
        shot("34-auto-download")
        back()
        row("Delete Old Messages").tap()
        sleep(1)
        shot("35-delete-old")
        app.buttons["delete-device-row"].tap()
        sleep(1)
        app.buttons["After 1 year"].tap()
        shot("36-delete-old-options")
        app.buttons["OK"].tap()
        XCTAssert(app.sheets.firstMatch.waitForExistence(timeout: 5))
        shot("37-delete-old-confirm")
        app.sheets.buttons["Cancel"].tap()
        sleep(1)
        app.buttons["Cancel"].tap()
        sleep(1)
        back()
        app.buttons["Export Backup"].tap()
        enterPasscode()
        XCTAssert(app.sheets.buttons["Start Backup"].waitForExistence(timeout: 10))
        shot("38-backup-sheet")
        app.sheets.buttons["Start Backup"].tap()
        XCTAssert(app.alerts["Backup successful"].waitForExistence(timeout: 60))
        shot("39-backup-done")
        app.alerts.buttons["OK"].tap()
        back()
        row("Notifications").tap()
        sleep(1)
        shot("40-notifications")
        back()
        row("Wallpaper").tap()
        sleep(2)
        shot("41-wallpaper")
        back()
        row("Advanced").tap()
        sleep(1)
        shot("42-advanced")
        app.buttons["Export Recovery Phrase"].tap()
        enterPasscode()
        XCTAssert(app.alerts["Export Recovery Phrase"].waitForExistence(timeout: 10))
        XCTAssert(app.alerts.buttons["Copy Recovery Phrase"].exists)
        app.alerts.buttons["Close"].tap()
        app.buttons["View Log"].tap()
        sleep(2)
        shot("43-log")
        toRoot()
        app.buttons["chats-title"].tap()
        sleep(2)
        shot("44-connectivity")
    }

    func test6Switcher() {
        switchTo("Alice")
        app.buttons["Switch Profile"].firstMatch.tap()
        let carol = app.buttons["profile-Carol"]
        XCTAssert(carol.waitForExistence(timeout: 5))
        carol.press(forDuration: 1.2)
        XCTAssert(app.buttons["Private Tag"].waitForExistence(timeout: 5))
        shot("45-switcher-menu")
        app.buttons["Private Tag"].tap()
        XCTAssert(app.alerts.textFields.firstMatch.waitForExistence(timeout: 5))
        app.alerts.textFields.firstMatch.typeText("Work")
        shot("46-private-tag")
        app.alerts.buttons["OK"].tap()
        sleep(1)
        carol.press(forDuration: 1.2)
        app.buttons["Mute Notifications"].tap()
        sleep(1)
        carol.press(forDuration: 1.2)
        app.buttons["Move to Top"].tap()
        sleep(1)
        shot("47-switcher-muted-tagged")
        carol.press(forDuration: 1.2)
        app.buttons["Unmute"].tap()
        sleep(1)
    }

    func deleteProfile(_ name: String, shots: Bool) {
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["profile-\(name)"].press(forDuration: 1.2)
        app.buttons["Delete"].tap()
        XCTAssert(app.sheets.buttons["Delete Profile"].waitForExistence(timeout: 5))
        if shots { shot("48-delete-profile-sheet") }
        app.sheets.buttons["Delete Profile"].tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 5))
        if shots { shot("49-delete-profile-alert") }
        app.alerts.buttons["Delete"].tap()
        sleep(3)
    }

    func test7BackupRestore() {
        if hasProfile("Dave") { deleteProfile("Dave", shots: false) }
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        app.buttons["Add Profile"].tap()
        createProfile("Dave")
        let phrase = readPasteboard()
        XCTAssertFalse(phrase.isEmpty)
        app.tabBars.buttons["Settings"].tap()
        row("Chats and Media").tap()
        app.buttons["Export Backup"].tap()
        enterPasscode()
        app.sheets.buttons["Start Backup"].tap()
        XCTAssert(app.alerts["Backup successful"].waitForExistence(timeout: 60))
        app.alerts.buttons["OK"].tap()
        deleteProfile("Dave", shots: true)
        toRoot()
        app.buttons["Switch Profile"].firstMatch.tap()
        XCTAssertFalse(app.buttons["profile-Dave"].exists)
        app.buttons["Add Profile"].tap()
        XCTAssert(app.buttons["Create New Profile"].waitForExistence(timeout: 10))
        app.buttons["I Already Have a Profile"].firstMatch.tap()
        sleep(1)
        shot("50-alternative-logins")
        app.sheets.buttons["Restore from Backup"].tap()
        let count = openBackupPicker(shotName: "51-backup-picker")
        XCTAssert(count > 0)
        app.cells.matching(NSPredicate(format: "label BEGINSWITH 'tapetape-backup'")).element(boundBy: 0).tap()
        XCTAssert(app.alerts["Restore from Backup"].waitForExistence(timeout: 10))
        shot("52-restore-prompt")
        app.alerts.textFields.firstMatch.typeText("abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about")
        app.alerts.buttons["OK"].tap()
        XCTAssert(app.alerts["Restore from Backup"].waitForExistence(timeout: 30))
        sleep(1)
        shot("53-restore-wrong-phrase")
        app.alerts.buttons["OK"].tap()
        var restored = false
        for index in 0..<count where !restored {
            app.buttons["I Already Have a Profile"].firstMatch.tap()
            app.sheets.buttons["Restore from Backup"].tap()
            _ = openBackupPicker(shotName: nil)
            app.cells.matching(NSPredicate(format: "label BEGINSWITH 'tapetape-backup'")).element(boundBy: index).tap()
            XCTAssert(app.alerts["Restore from Backup"].waitForExistence(timeout: 10))
            app.alerts.textFields.firstMatch.typeText(phrase)
            app.alerts.buttons["OK"].tap()
            let gone = expectation(for: NSPredicate(format: "exists == false"), evaluatedWith: app.buttons["I Already Have a Profile"].firstMatch)
            if XCTWaiter().wait(for: [gone], timeout: 30) == .completed {
                restored = true
            } else {
                app.alerts.buttons["OK"].firstMatch.tap()
            }
        }
        XCTAssert(restored)
        sleep(2)
        shot("54-restored")
        app.buttons["Switch Profile"].firstMatch.tap()
        XCTAssert(app.buttons["profile-Dave"].waitForExistence(timeout: 5))
        shot("55-restored-switcher")
        app.navigationBars.buttons["Close"].firstMatch.tap()
        sleep(1)
        deleteProfile("Dave", shots: false)
    }

    func attachFile(_ prefix: String) {
        app.buttons["Add Attachment"].tap()
        sleep(1)
        app.buttons["Files"].firstMatch.tap()
        sleep(2)
        let browse = app.buttons["Browse"].firstMatch
        if browse.waitForExistence(timeout: 3) { browse.tap(); sleep(1) }
        let local = app.staticTexts["On My iPhone"].firstMatch
        if local.waitForExistence(timeout: 3) { local.tap(); sleep(1) }
        let file = app.cells.containing(NSPredicate(format: "label BEGINSWITH %@", prefix)).firstMatch
        XCTAssert(file.waitForExistence(timeout: 5))
        file.tap()
        sleep(2)
    }

    func test8LargeAttachment() {
        app.terminate()
        app.launchArguments += ["-TapeTapeFilesBackend", "https://tapetape.org"]
        app.launch()
        switchTo("Bob")
        app.tabBars.buttons["Settings"].tap()
        row("Chats and Media").tap()
        row("Auto-Download Messages").tap()
        app.buttons["Up to 160 KiB, most worse quality images"].tap()
        switchTo("Alice")
        openChat("Bob")
        attachFile("tapetape-large")
        shot("56-large-staged")
        app.buttons["Send"].tap()
        sleep(3)
        shot("57-large-uploading")
        sleep(25)
        shot("58-large-sent")
        switchTo("Bob")
        openChat("Alice")
        sleep(5)
        shot("59-large-available")
        let download = app.staticTexts.matching(NSPredicate(format: "label BEGINSWITH 'Download'")).firstMatch
        if download.waitForExistence(timeout: 30) {
            download.tap()
            sleep(2)
            shot("60-large-downloading")
            sleep(25)
            shot("61-large-downloaded")
        }
        toRoot()
        app.tabBars.buttons["Settings"].tap()
        row("Chats and Media").tap()
        row("Auto-Download Messages").tap()
        app.buttons["All"].tap()
        app.terminate()
        app.launchArguments = ["-AppleLanguages", "(en)", "-AppleLocale", "en_US", "-TapeTapeFilesBackend", ""]
        app.launch()
        sleep(2)
    }

    func test9Notifications() {
        switchTo("Alice")
        switchTo("Bob")
        openChat("Alice")
        send("Ping for notification")
        sleep(15)
        toRoot()
        XCUIDevice.shared.press(.home)
        sleep(2)
        let top = springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.005))
        top.press(forDuration: 0.1, thenDragTo: springboard.coordinate(withNormalizedOffset: CGVector(dx: 0.3, dy: 0.6)))
        sleep(2)
        shot("62-notification-center")
        let note = springboard.descendants(matching: .any).matching(NSPredicate(format: "label CONTAINS 'Ping for notification'")).firstMatch
        XCTAssert(note.waitForExistence(timeout: 10))
    }

    func test10ShareExtension() {
        switchTo("Alice")
        app.terminate()
        let photos = XCUIApplication(bundleIdentifier: "com.apple.mobileslideshow")
        photos.launch()
        sleep(3)
        let continueButton = photos.buttons.matching(NSPredicate(format: "label IN {'Continue', '继续'}")).firstMatch
        if continueButton.waitForExistence(timeout: 3) { continueButton.tap() }
        let image = photos.images.matching(identifier: "PXGGridLayout-Info").firstMatch
        XCTAssert(image.waitForExistence(timeout: 10))
        image.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.5)).tap()
        sleep(3)
        try? photos.debugDescription.write(toFile: dir + "/photos-tree.txt", atomically: true, encoding: .utf8)
        photos.buttons.matching(NSPredicate(format: "label IN {'Share', '共享', '分享'}")).firstMatch.tap()
        sleep(2)
        shot("64-share-sheet")
        var target = photos.cells.matching(NSPredicate(format: "label == 'TapeTape'")).firstMatch
        if !target.waitForExistence(timeout: 3) {
            target = photos.buttons.matching(NSPredicate(format: "label == 'TapeTape'")).firstMatch
        }
        if !target.exists {
            photos.collectionViews.cells.matching(NSPredicate(format: "label IN {'More', '更多'}")).firstMatch.tap()
            sleep(2)
            shot("64b-share-more")
            target = photos.cells.matching(NSPredicate(format: "label CONTAINS 'TapeTape'")).firstMatch
        }
        XCTAssert(target.waitForExistence(timeout: 5))
        target.tap()
        sleep(3)
        shot("65-share-compose")
        let text = photos.textViews.firstMatch
        if text.waitForExistence(timeout: 5) {
            text.tap()
            text.typeText("Shared from Photos")
        }
        let forward = photos.staticTexts.matching(NSPredicate(format: "label IN {'Forward to…', '转发给…'}")).firstMatch
        if forward.waitForExistence(timeout: 3) {
            forward.tap()
            sleep(2)
            shot("66-share-chat-picker")
            photos.staticTexts["Bob"].firstMatch.tap()
            sleep(1)
        }
        shot("67-share-ready")
        photos.buttons.matching(NSPredicate(format: "label IN {'Send', 'Post', '发送', '发布'}")).firstMatch.tap()
        sleep(8)
        photos.terminate()
        app.launch()
        switchTo("Alice")
        openChat("Bob")
        sleep(3)
        shot("68-share-arrived")
    }

    func addProxyLink(_ link: String) {
        app.buttons["Add Proxy"].tap()
        let field = app.alerts.textFields.firstMatch
        XCTAssert(field.waitForExistence(timeout: 5))
        field.typeText(link)
        app.alerts.buttons["Use Proxy"].tap()
        sleep(2)
    }

    func test11Proxy() {
        switchTo("Alice")
        app.tabBars.buttons["Settings"].tap()
        row("Advanced").tap()
        shot("69-advanced-proxy-row")
        row("Proxy").tap()
        shot("70-proxy-empty")
        app.buttons["Add Proxy"].tap()
        XCTAssert(app.alerts.textFields.firstMatch.waitForExistence(timeout: 5))
        app.alerts.textFields.firstMatch.typeText("not a proxy")
        shot("71-proxy-add-alert")
        app.alerts.buttons["Use Proxy"].tap()
        XCTAssert(app.alerts["Invalid or unsupported proxy"].waitForExistence(timeout: 5))
        shot("72-proxy-invalid")
        app.alerts.buttons.firstMatch.tap()
        addProxyLink("socks5://127.0.0.1:9")
        sleep(4)
        shot("73-proxy-active")
        let row = app.buttons.matching(NSPredicate(format: "label BEGINSWITH '127.0.0.1'")).firstMatch
        XCTAssert(row.waitForExistence(timeout: 5))
        row.swipeRight()
        sleep(1)
        app.buttons["Share"].firstMatch.tap()
        sleep(2)
        shot("74-proxy-share")
        app.swipeDown(velocity: .fast)
        sleep(1)
        app.switches["Use Proxy"].firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.9, dy: 0.5)).tap()
        sleep(2)
        toRoot()
        shot("75-chats-shield")
        app.buttons["Proxy"].firstMatch.tap()
        sleep(1)
        let saved = app.buttons.matching(NSPredicate(format: "label BEGINSWITH '127.0.0.1'")).firstMatch
        saved.swipeLeft()
        sleep(1)
        app.buttons["Delete"].firstMatch.tap()
        XCTAssert(app.sheets.buttons["Delete Proxy"].waitForExistence(timeout: 5))
        shot("76-proxy-delete-confirm")
        app.sheets.buttons["Delete Proxy"].tap()
        sleep(1)
        toRoot()
        XCTAssertFalse(app.buttons["Proxy"].exists)
        openChat("Bob")
        send("https://t.me/socks?server=127.0.0.2&port=1080")
        sleep(2)
        app.links.matching(NSPredicate(format: "label CONTAINS 't.me'")).allElementsBoundByIndex.last?.tap()
        XCTAssert(app.alerts.buttons["Use Proxy"].waitForExistence(timeout: 5))
        shot("77-proxy-link-confirm")
        app.alerts.buttons["Cancel"].tap()
        sleep(1)
        toRoot()
        sleep(1)
        app.buttons["Switch Profile"].firstMatch.tap()
        if !app.buttons["Add Profile"].waitForExistence(timeout: 3) {
            app.swipeUp()
            sleep(1)
        }
        XCTAssert(app.buttons["Add Profile"].waitForExistence(timeout: 5))
        app.buttons["Add Profile"].tap()
        XCTAssert(app.buttons["Create New Profile"].waitForExistence(timeout: 15))
        app.buttons["Create New Profile"].tap()
        sleep(2)
        app.navigationBars.buttons["More"].firstMatch.tap()
        sleep(1)
        shot("78-setup-proxy-menu")
        app.buttons["Use Proxy"].tap()
        sleep(1)
        shot("79-setup-proxy-sheet")
        app.navigationBars["Proxy"].buttons.firstMatch.tap()
        sleep(1)
        back()
        app.buttons["Cancel"].firstMatch.tap()
        sleep(1)
    }

    func test12UploadError() {
        switchTo("Alice")
        openChat("Bob")
        let file = app.staticTexts.matching(NSPredicate(format: "label == 'tapetape-large.bin'")).allElementsBoundByIndex.last
        XCTAssertNotNil(file)
        file?.tap()
        XCTAssert(app.alerts.firstMatch.waitForExistence(timeout: 5))
        shot("61b-large-upload-error")
        app.alerts.buttons.firstMatch.tap()
    }
}
