import Foundation
import XCTest
@testable import FinexyCore

private final class StubURLProtocol: URLProtocol {
    static var handler: ((URLRequest) throws -> (Int, Data))?

    override class func canInit(with request: URLRequest) -> Bool { true }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        do {
            let (status, data) = try Self.handler!(request)
            let response = HTTPURLResponse(url: request.url!, statusCode: status, httpVersion: nil, headerFields: ["Content-Type": "application/json"])!
            client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
            client?.urlProtocol(self, didLoad: data)
            client?.urlProtocolDidFinishLoading(self)
        } catch { client?.urlProtocol(self, didFailWithError: error) }
    }
    override func stopLoading() {}
}

final class APIClientTests: XCTestCase {
    private var session: URLSession!
    private var client: APIClient!

    override func setUpWithError() throws {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [StubURLProtocol.self]
        session = URLSession(configuration: configuration)
        client = APIClient(server: try ServerAddress(rawValue: "https://example.test/finexy"), session: session)
    }

    override func tearDown() {
        session.invalidateAndCancel()
        StubURLProtocol.handler = nil
    }

    func testLoginAndTwoFactorUseWireContract() async throws {
        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/authorize.json")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertNil(request.value(forHTTPHeaderField: "Authorization"))
            XCTAssertNotNil(Int(request.value(forHTTPHeaderField: "X-Timezone-Offset") ?? ""))
            let body = try self.bodyData(request)
            let object = try XCTUnwrap(JSONSerialization.jsonObject(with: body) as? [String: String])
            XCTAssertEqual(Set(object.keys), ["loginName", "password"])
            return (200, Data(#"{"success":true,"result":{"token":"challenge-fixture","need2FA":true,"user":null}}"#.utf8))
        }
        let challenge = try await client.authorize(loginName: "fixture", password: "fixture-password")
        XCTAssertTrue(challenge.need2FA)
        XCTAssertNil(challenge.user)

        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/2fa/recovery.json")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer challenge-fixture")
            let object = try JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: String]
            XCTAssertEqual(object, ["recoveryCode": "fixture-code"])
            return (200, Data(#"{"success":true,"result":{"token":"session-fixture","need2FA":false,"user":{"username":"fixture","nickname":"测试用户","defaultCurrency":"CNY"}}}"#.utf8))
        }
        let authorized = try await client.authorizeTwoFactor(token: challenge.token, code: "fixture-code", recovery: true)
        XCTAssertFalse(authorized.need2FA)
        XCTAssertEqual(authorized.user?.username, "fixture")
    }

    func testProfileIsFlatAndLedgerIDsStayExact() async throws {
        StubURLProtocol.handler = { request in
            if request.url?.path.hasSuffix("users/profile/get.json") == true {
                return (200, Data(#"{"success":true,"result":{"username":"fixture","nickname":"","defaultCurrency":"JPY"}}"#.utf8))
            }
            return (200, Data(#"{"success":true,"result":[{"id":"9223372036854775807","name":"共享账本","comment":""}]}"#.utf8))
        }
        let profile = try await client.profile(token: "fixture")
        XCTAssertEqual(profile.displayName, "fixture")
        let ledgers = try await client.ledgers(token: "fixture")
        XCTAssertEqual(ledgers.map(\.id), ["0", "9223372036854775807"])
    }

    func testTransactionsKeepIntegerPrecisionAndScopeInCursorRequest() async throws {
        StubURLProtocol.handler = { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertEqual(query.first(where: { $0.name == "ledgerId" })?.value, "42")
            XCTAssertEqual(query.first(where: { $0.name == "max_time" })?.value, "9223372036854775807")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture")
            return (200, Self.transactionResponse(ledger: "42", cursor: "9223372036854775806"))
        }
        let page = try await client.transactions(token: "fixture", ledgerID: "42", cursor: "9223372036854775807")
        let transaction = try XCTUnwrap(page.items.first)
        XCTAssertEqual(transaction.id, "9223372036854775807")
        XCTAssertEqual(transaction.sourceAmount, 9_223_372_036_854_775_807)
        XCTAssertTrue(transaction.hideAmount)
        XCTAssertNil(transaction.destinationAccountId)
        XCTAssertEqual(page.nextTimeSequenceId, "9223372036854775806")
    }

    func testCategoryGroupsAndNestedAccountsUseLedgerQuery() async throws {
        StubURLProtocol.handler = { request in
            let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
            XCTAssertEqual(query.first(where: { $0.name == "ledgerId" })?.value, "42")
            if request.url?.path.contains("categories") == true {
                return (200, Data(#"{"success":true,"result":{"2":[{"id":"10","parentId":"0","name":"餐饮","type":2,"hidden":false,"subCategories":[{"id":"11","parentId":"10","name":"午餐","type":2,"hidden":false}]}]}}"#.utf8))
            }
            return (200, Data(#"{"success":true,"result":[{"id":"1","ledgerId":"42","parentId":"0","name":"现金","currency":"JPY","balance":12345,"hidden":false}]}"#.utf8))
        }
        let categories = try await client.categories(token: "fixture", ledgerID: "42")
        XCTAssertEqual(categories.flatMap(\.flattened).map(\.id), ["10", "11"])
        let accounts = try await client.accounts(token: "fixture", ledgerID: "42")
        XCTAssertEqual(accounts.first?.currency, "JPY")
    }

    func testFailureEnvelopeIsReadBeforeUnexpectedResultTypeAndNeverEchoesServerText() async {
        StubURLProtocol.handler = { _ in
            (401, Data(#"{"success":false,"errorCode":202003,"errorMessage":"sensitive fixture never display","result":false}"#.utf8))
        }
        do {
            _ = try await client.profile(token: "fixture")
            XCTFail("Expected server error")
        } catch let error as APIError {
            XCTAssertTrue(error.isUnauthorized)
            XCTAssertFalse(error.localizedDescription.contains("sensitive"))
        } catch { XCTFail("Unexpected error type") }
    }

    func testIncompleteResponsesAreNotEmptyAndHTTPRedirectIsRejected() async {
        for (status, json, expected) in [
            (200, #"{"success":true}"#, APIError.invalidResponse),
            (200, #"{"success":true,"result":null}"#, APIError.invalidResponse),
            (302, "<html>redirect</html>", APIError.httpStatus(302))
        ] {
            StubURLProtocol.handler = { _ in (status, Data(json.utf8)) }
            do {
                _ = try await client.ledgers(token: "fixture")
                XCTFail("Expected malformed response error")
            } catch let error as APIError { XCTAssertEqual(error, expected) }
            catch { XCTFail("Unexpected error type") }
        }
    }

    func testCrossLedgerAndNonDescendingCursorAreRejected() async {
        for (ledger, cursor, expected) in [
            ("43", "99", APIError.ledgerMismatch),
            ("42", "100", APIError.invalidCursor)
        ] {
            StubURLProtocol.handler = { _ in (200, Self.transactionResponse(ledger: ledger, cursor: cursor)) }
            do {
                _ = try await client.transactions(token: "fixture", ledgerID: "42", cursor: "100")
                XCTFail("Expected scope or cursor error")
            } catch let error as APIError { XCTAssertEqual(error, expected) }
            catch { XCTFail("Unexpected error type") }
        }
    }

    func testZeroCursorMeansEndOfHistory() async throws {
        StubURLProtocol.handler = { _ in (200, Self.transactionResponse(ledger: "42", cursor: "0")) }
        let page = try await client.transactions(token: "fixture", ledgerID: "42")
        XCTAssertNil(page.nextTimeSequenceId)
        XCTAssertEqual(page.items.count, 1)
    }

    func testMoneyUsesHundredthsEvenForJPYAndInt64Extremes() {
        let locale = Locale(identifier: "en_US")
        XCTAssertEqual(MoneyFormatter.format(minorUnits: 12345, currency: "JPY", locale: locale), "JPY 123.45")
        XCTAssertEqual(MoneyFormatter.format(minorUnits: Int64.max, currency: "CNY", locale: locale), "CNY 92,233,720,368,547,758.07")
        XCTAssertEqual(MoneyFormatter.format(minorUnits: Int64.min, currency: "CNY", locale: locale), "CNY -92,233,720,368,547,758.08")
    }

    func testServerAddressPreservesSubpathAndRejectsAmbiguousAddresses() throws {
        let server = try ServerAddress(rawValue: " HTTPS://EXAMPLE.TEST:443/finexy/ ")
        XCTAssertEqual(server.rawValue, "https://example.test/finexy/")
        XCTAssertTrue(server.isSecure)
        let localServer = try ServerAddress(rawValue: "http://192.168.1.2:8080")
        XCTAssertFalse(localServer.isSecure)
        for invalid in ["example.test", "ftp://example.test", "https://user:password@example.test", "https://example.test?a=1", "https://example.test#settings", "https://example.test/../other", "https://example.test:0", "https://example.test:65536"] {
            XCTAssertThrowsError(try ServerAddress(rawValue: invalid))
        }
    }

    private func bodyData(_ request: URLRequest) throws -> Data {
        if let body = request.httpBody { return body }
        guard let stream = request.httpBodyStream else { return Data() }
        stream.open()
        defer { stream.close() }
        var data = Data()
        var buffer = [UInt8](repeating: 0, count: 1024)
        while stream.hasBytesAvailable {
            let count = stream.read(&buffer, maxLength: buffer.count)
            if count < 0 { throw stream.streamError! }
            if count == 0 { break }
            data.append(contentsOf: buffer.prefix(count))
        }
        return data
    }

    private static func transactionResponse(ledger: String, cursor: String) -> Data {
        Data("""
        {"success":true,"result":{"items":[{"id":"9223372036854775807","ledgerId":"\(ledger)","timeSequenceId":"9007199254740994","type":3,"time":1794100000,"utcOffset":480,"categoryId":"0","sourceAccountId":"1","sourceAmount":9223372036854775807,"hideAmount":true,"comment":"fixture"}],"nextTimeSequenceId":"\(cursor)","totalCount":1}}
        """.utf8)
    }
}
