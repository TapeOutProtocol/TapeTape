import Foundation
import os
import TapeTapeCore

private let missing = "\u{0}"
private let englishBundle = Bundle.main.path(forResource: "en", ofType: "lproj").flatMap(Bundle.init(path:)) ?? .main

func L(_ key: String) -> String {
    for table in ["Localizable", "TapeTape"] {
        let s = Bundle.main.localizedString(forKey: key, value: missing, table: table)
        if s != missing { return s }
    }
    for table in ["Localizable", "TapeTape"] {
        let s = englishBundle.localizedString(forKey: key, value: missing, table: table)
        if s != missing { return s }
    }
    return key
}

func L(_ key: String, _ args: CVarArg...) -> String {
    String(format: L(key), arguments: args)
}

func L(_ key: String, arguments: [CVarArg]) -> String {
    String(format: L(key), arguments: arguments)
}

func LPlural(_ key: String, _ count: Int) -> String {
    String.localizedStringWithFormat(NSLocalizedString(key, comment: ""), count)
}

func coreMessage(_ error: Error) -> String {
    if case CoreError.Failed(let message) = error {
        switch message {
        case "group-full": return L("v2_group_full")
        case "too-large": return L("relay_too_large")
        default: return message
        }
    }
    return error.localizedDescription
}

func relayErrorText(_ code: String) -> String {
    switch code {
    case "sender-not-allowed": return L("relay_not_accepted")
    case "recipient-no-key": return L("relay_no_key")
    case "too-large": return L("relay_too_large")
    case "quota-exceeded": return L("v2_upload_quota_exceeded")
    case "not-configured": return L("v2_upload_not_configured")
    case "upload-failed": return L("v2_upload_failed")
    default: return code
    }
}

enum Log {
    static let app = Logger(subsystem: "org.tapetape.v2", category: "app")
    static let onboarding = Logger(subsystem: "org.tapetape.v2", category: "onboarding")
    static let chat = Logger(subsystem: "org.tapetape.v2", category: "chat")
    static let invite = Logger(subsystem: "org.tapetape.v2", category: "invite")
}
