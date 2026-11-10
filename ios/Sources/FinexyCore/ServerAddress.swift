import Foundation

/// The installation root, including a reverse proxy subpath when present.
public struct ServerAddress: Codable, Hashable, Sendable {
    public let url: URL
    public var rawValue: String { url.absoluteString }
    public var isSecure: Bool { url.scheme == "https" }

    public init(rawValue: String) throws {
        let input = rawValue.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !input.isEmpty,
              !input.contains("\\"),
              input.rangeOfCharacter(from: .controlCharacters) == nil,
              var components = URLComponents(string: input),
              let scheme = components.scheme?.lowercased(),
              ["https", "http"].contains(scheme),
              let host = components.host, !host.isEmpty,
              components.user == nil, components.password == nil,
              components.query == nil, components.fragment == nil,
              components.port.map({ (1...65535).contains($0) }) ?? true,
              !components.path.split(separator: "/").contains(where: { $0 == "." || $0 == ".." })
        else { throw APIError.invalidServerAddress }
        components.scheme = scheme
        components.host = host.lowercased()
        if (scheme == "https" && components.port == 443) || (scheme == "http" && components.port == 80) {
            components.port = nil
        }
        components.path = components.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))
        components.path = components.path.isEmpty ? "/" : "/" + components.path + "/"
        guard let url = components.url else { throw APIError.invalidServerAddress }
        self.url = url
    }

    public init(from decoder: Decoder) throws {
        let container = try decoder.singleValueContainer()
        try self.init(rawValue: container.decode(String.self))
    }

    public func encode(to encoder: Encoder) throws {
        var container = encoder.singleValueContainer()
        try container.encode(rawValue)
    }
}
