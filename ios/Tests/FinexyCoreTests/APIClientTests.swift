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

    func testMonthlyReportPreservesNaturalMonthScopeAndIntegerTotals() async throws {
        let date = try XCTUnwrap(ISO8601DateFormatter().date(from: "2024-01-31T18:00:00Z"))
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let month = LedgerReportMonth(date: date, timeZone: zone)
        XCTAssertEqual(month.startDate, "2024-02-01")
        XCTAssertEqual(month.endDate, "2024-02-29")
        XCTAssertEqual(month.moved(by: -2)?.startDate, "2023-12-01")

        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/reports/query.json")
            XCTAssertEqual(request.httpMethod, "POST")
            XCTAssertEqual(request.value(forHTTPHeaderField: "Authorization"), "Bearer fixture")
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: Any])
            let query = try XCTUnwrap(body["query"] as? [String: Any])
            XCTAssertEqual(query["ledgerId"] as? String, "9007199254740993")
            XCTAssertEqual(query["startDate"] as? String, "2024-02-01")
            XCTAssertEqual(query["endDate"] as? String, "2024-02-29")
            XCTAssertEqual(query["timezone"] as? String, "Asia/Shanghai")
            XCTAssertEqual(query["hidden"] as? String, "exclude")
            XCTAssertEqual((query["accounts"] as? [String: Any])?["ids"] as? [String], ["23"])
            return (200, Self.reportResponse())
        }
        let report = try await client.monthlyReport(token: "fixture", ledgerID: "9007199254740993", month: month, accountID: "23")
        XCTAssertEqual(report.totals.first?.incomeMinor, 9_007_199_254_740_993)
        XCTAssertEqual(report.details.first?.source?.amountMinor, 9_007_199_254_740_993)
        XCTAssertEqual(report.matchedCount, 1)
        XCTAssertNil(report.nextOffset)
    }

    func testMonthlyReportRejectsCrossLedgerIncompleteAndHiddenResponses() async throws {
        let date = try XCTUnwrap(ISO8601DateFormatter().date(from: "2024-02-01T12:00:00Z"))
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let month = LedgerReportMonth(date: date, timeZone: zone)
        let responses: [(Data, APIError)] = [
            (Self.reportResponse(ledger: "42"), .ledgerMismatch),
            (Self.reportResponse(complete: false), .invalidResponse),
            (Self.reportResponse(hidden: true), .invalidResponse),
        ]
        for (data, expected) in responses {
            StubURLProtocol.handler = { _ in (200, data) }
            do {
                _ = try await client.monthlyReport(token: "fixture", ledgerID: "9007199254740993", month: month, accountID: "23")
                XCTFail("Expected report rejection")
            } catch let error as APIError {
                XCTAssertEqual(error, expected)
            }
        }
    }

    func testMonthlyReportContinuationRequiresTheSameSnapshot() async throws {
        let date = try XCTUnwrap(ISO8601DateFormatter().date(from: "2024-02-01T12:00:00Z"))
        let zone = try XCTUnwrap(TimeZone(identifier: "Asia/Shanghai"))
        let month = LedgerReportMonth(date: date, timeZone: zone)
        let hash = String(repeating: "f", count: 64)
        StubURLProtocol.handler = { request in
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: Any])
            XCTAssertEqual(body["offset"] as? Int, 50)
            XCTAssertEqual(body["expectedSnapshotHash"] as? String, hash)
            return (200, Self.reportResponse(offset: 50, matchedCount: 51))
        }
        let page = try await client.monthlyReport(token: "fixture", ledgerID: "9007199254740993", month: month,
                                                   accountID: "23", offset: 50, snapshotHash: hash)
        XCTAssertEqual(page.matchedCount, 51)
        XCTAssertEqual(page.details.count, 1)
        XCTAssertNil(page.nextOffset)

        StubURLProtocol.handler = { _ in
            (200, Self.reportResponse(offset: 50, matchedCount: 51, hash: String(repeating: "a", count: 64)))
        }
        do {
            _ = try await client.monthlyReport(token: "fixture", ledgerID: "9007199254740993", month: month,
                                               accountID: "23", offset: 50, snapshotHash: hash)
            XCTFail("Expected changed snapshot rejection")
        } catch let error as APIError {
            XCTAssertEqual(error, .invalidResponse)
        }
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

    func testDurableCreationUsesStableStringIDsAndIntegerAmounts() async throws {
        let draft = TransactionDraft(type: .transfer, categoryID: "44", time: 1794100000, utcOffset: 480,
                                     sourceAccountID: "0", destinationAccountID: "9223372036854775807",
                                     sourceAmount: 0, destinationAmount: 9_999_999_999_999)
        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/systems/version.json")
            return (200, Data(#"{"success":true,"result":{"version":"","transactionCreateProtocol":"durable-receipt-v1"}}"#.utf8))
        }
        let writeProtocol = try await client.requireDurableTransactionWrites(token: "fixture")
        XCTAssertEqual(writeProtocol, .dedicated)
        let original = TransactionCreateRequest(ledgerID: "42", draft: draft, clientSessionID: "ios-fixture-uuid",
                                                writeProtocol: writeProtocol)
        let persisted = try JSONDecoder().decode(TransactionCreateRequest.self, from: JSONEncoder().encode(original))
        XCTAssertEqual(persisted, original)
        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/add_durable.json")
            XCTAssertEqual(request.httpMethod, "POST")
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: Any])
            XCTAssertEqual(body["ledgerId"] as? String, "42")
            XCTAssertEqual(body["clientSessionId"] as? String, "ios-fixture-uuid")
            XCTAssertEqual(body["destinationAccountId"] as? String, "9223372036854775807")
            XCTAssertEqual(body["destinationAmount"] as? Int64, 9_999_999_999_999)
            XCTAssertEqual(body["sourceAccountId"] as? String, "0")
            XCTAssertEqual(body["sourceAmount"] as? Int, 0)
            XCTAssertEqual(body["type"] as? Int, 4)
            return (200, Self.transactionDetailResponse(ledger: "42", editable: false))
        }
        let first = try await client.addTransaction(token: "fixture", request: original)
        let replay = try await client.addTransaction(token: "fixture", request: persisted)
        XCTAssertEqual(first.id, replay.id)
    }

    func testOlderAndPrereleaseServersCannotReceiveCreationRequests() async {
        let draft = TransactionDraft(type: .expense, categoryID: "11", time: 1794100000, utcOffset: 480,
                                     sourceAccountID: "1", sourceAmount: -123)
        let submission = TransactionCreateRequest(ledgerID: "0", draft: draft, clientSessionID: "ios-fixture-uuid",
                                                  writeProtocol: .releaseCompatible)
        for version in ["", "1.9.3", "1.9.4-rc.1"] {
            StubURLProtocol.handler = { request in
                XCTAssertEqual(request.url?.path, "/finexy/api/v1/systems/version.json")
                return (200, Data("""
                {"success":true,"result":{"version":"\(version)"}}
                """.utf8))
            }
            do {
                _ = try await client.addTransaction(token: "fixture", request: submission)
                XCTFail("Expected unsupported server")
            } catch let error as APIError {
                XCTAssertEqual(error, .unsupportedTransactionWrites)
                XCTAssertTrue(error.isDefinitiveTransactionRejection)
            } catch { XCTFail("Unexpected error type") }
        }
        StubURLProtocol.handler = { _ in (200, Data(#"{"success":true,"result":{"version":"1.9.4"}}"#.utf8)) }
        do { try await client.requireDurableTransactionWrites(token: "fixture") }
        catch { XCTFail("Release supports durable add") }
    }

    func testConditionalEditPreservesUnmanagedFieldsByOmissionAndHandlesConflict() async throws {
        let editToken = String(repeating: "a", count: 64)
        StubURLProtocol.handler = { request in
            if request.httpMethod == "GET" {
                XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/get.json")
                let query = URLComponents(url: request.url!, resolvingAgainstBaseURL: false)?.queryItems ?? []
                XCTAssertEqual(query.first(where: { $0.name == "ledgerId" })?.value, "42")
                return (200, Self.transactionDetailResponse(ledger: "42", editable: true, editToken: editToken))
            }
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/modify_preserving_metadata.json")
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: Any])
            XCTAssertEqual(body["expectedEditToken"] as? String, editToken)
            XCTAssertEqual(body["id"] as? String, "9223372036854775807")
            XCTAssertEqual(body["sourceAmount"] as? Int, -123)
            XCTAssertNil(body["tagIds"])
            XCTAssertNil(body["pictureIds"])
            XCTAssertNil(body["geoLocation"])
            return (409, Data(#"{"success":false,"errorCode":205042,"result":false}"#.utf8))
        }
        let detail = try await client.transaction(token: "fixture", ledgerID: "42", id: "9223372036854775807")
        XCTAssertTrue(detail.canEdit)
        let draft = TransactionDraft(type: .expense, categoryID: "11", time: 1794100000, utcOffset: 480,
                                     sourceAccountID: "1", sourceAmount: -123)
        do {
            _ = try await client.modifyTransaction(token: "fixture", ledgerID: "42", detail: detail, draft: draft)
            XCTFail("Expected conflict")
        } catch let error as APIError {
            XCTAssertEqual(error.localizedDescription, APIError.transactionChanged.localizedDescription)
            XCTAssertFalse(error.isAmbiguousTransactionWrite)
        }
    }

    func testConditionalDeleteSendsTheObservedTokenWithoutLegacyFallback() async throws {
        let editToken = String(repeating: "e", count: 64)
        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/get.json")
            return (200, Self.transactionDetailResponse(ledger: "42", editable: true, editToken: editToken))
        }
        let detail = try await client.transaction(token: "fixture", ledgerID: "42", id: "9223372036854775807")
        StubURLProtocol.handler = { request in
            XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/delete_if_unchanged.json")
            let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: String])
            XCTAssertEqual(body, ["ledgerId": "42", "id": "9223372036854775807", "expectedEditToken": editToken])
            return (404, Data())
        }
        do {
            try await client.deleteTransaction(token: "fixture", ledgerID: "42", detail: detail)
            XCTFail("Expected no route on an older node")
        } catch let error as APIError {
            XCTAssertEqual(error, .httpStatus(404))
        }
    }

    func testOlderDetailsCanDeleteButCannotModifyAndRoleIsAuthoritative() async throws {
        StubURLProtocol.handler = { request in
            if request.url?.path.hasSuffix("member/list.json") == true {
                return (200, Data(#"{"success":true,"result":[{"ledgerId":"42","role":1,"status":1},{"ledgerId":"42","role":4,"status":1,"isCurrentUser":true}]}"#.utf8))
            }
            if request.httpMethod == "POST" {
                XCTAssertEqual(request.url?.path, "/finexy/api/v1/transactions/delete.json")
                let body = try XCTUnwrap(JSONSerialization.jsonObject(with: self.bodyData(request)) as? [String: String])
                XCTAssertEqual(body, ["ledgerId": "42", "id": "9223372036854775807"])
                return (200, Data(#"{"success":true,"result":true}"#.utf8))
            }
            return (200, Self.transactionDetailResponse(ledger: "42", editable: true))
        }
        let viewer = try await client.ledgerRole(token: "fixture", ledgerID: "42")
        XCTAssertEqual(viewer, .viewer)
        XCTAssertFalse(viewer.canWrite)
        let detail = try await client.transaction(token: "fixture", ledgerID: "42", id: "9223372036854775807")
        XCTAssertFalse(detail.canEdit)
        XCTAssertTrue(detail.canDelete)
        try await client.deleteTransaction(token: "fixture", ledgerID: "42", detail: detail)
    }

    func testMoneyEntryParsesExactAmountsAndRejectsExcessPrecision() {
        let locale = Locale(identifier: "de_DE")
        XCTAssertEqual(MoneyFormatter.parseMinorUnits("−12,30", locale: locale), -1230)
        XCTAssertEqual(MoneyFormatter.parseMinorUnits("12.3", locale: locale), 1230)
        XCTAssertEqual(MoneyFormatter.parseMinorUnits("９９９９９９９９９９９,９９", locale: locale), 9_999_999_999_999)
        for invalid in ["", ".", "0,001", "1.000,00", "100000000000", "2e1", "²"] {
            XCTAssertNil(MoneyFormatter.parseMinorUnits(invalid, locale: locale), invalid)
        }
    }

    private static func transactionDetailResponse(ledger: String, editable: Bool, editToken: String? = nil) -> Data {
        let tokenField = editToken.map { ",\"editToken\":\"\($0)\"" } ?? ""
        return Data("""
        {"success":true,"result":{"id":"9223372036854775807","ledgerId":"\(ledger)","timeSequenceId":"9007199254740994","type":3,"time":1794100000,"utcOffset":480,"categoryId":"11","sourceAccountId":"1","sourceAmount":123,"hideAmount":false,"comment":"fixture","editable":\(editable)\(tokenField)}}
        """.utf8)
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

    private static func reportResponse(ledger: String = "9007199254740993", complete: Bool = true, hidden: Bool = false,
                                       offset: Int = 0, matchedCount: Int = 1, hash: String = String(repeating: "f", count: 64)) -> Data {
        let money = #"{"currency":"JPY","income":"9007199254740993","expense":"0","net":"9007199254740993"}"#
        return Data("""
        {"success":true,"result":{
          "query":{"ledgerId":"\(ledger)","startDate":"2024-02-01","endDate":"2024-02-29","timezone":"Asia/Shanghai","types":[1,2,3,4],"accounts":{"ids":["23"],"mode":"include","includeDescendants":false},"hidden":"exclude"},
          "dataComplete":\(complete),"offset":\(offset),"returnedCount":1,"detailsComplete":\(offset == 0 && matchedCount == 1),"snapshotHash":"\(hash)","matchedCount":\(matchedCount),"nextOffset":null,
          "details":[{"id":"42","type":2,"date":"2024-02-29","categoryId":"9","categoryName":"收入","source":{"accountId":"23","accountName":"现金","currency":"JPY","amount":"9007199254740993"},"destination":null,"hidden":\(hidden),"comment":"fixture","adjustment":"0"}],
          "totals":[\(money)],"categories":[{"key":"9","name":"收入","count":\(matchedCount),"money":[\(money)]}]
        }}
        """.utf8)
    }
}
