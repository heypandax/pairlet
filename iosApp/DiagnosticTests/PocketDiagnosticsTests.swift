import XCTest
import Sentry
import zlib
@testable import cc_pocket

final class PocketDiagnosticsTests: XCTestCase {
    override func tearDown() {
        PocketDiagnostics.shared.stopForTesting()
        SentrySDK.setUser(nil)
        super.tearDown()
    }

    func testFinalEnvelopesDropSDKEnrichmentAndPreserveSafeCaptureFrames() async throws {
        XCTAssertEqual(ProcessInfo.processInfo.environment["CCPOCKET_TEST_MODE"], "1")
        let config = URLSessionConfiguration.ephemeral
        config.protocolClasses = [EnvelopeProtocol.self]
        EnvelopeProtocol.reset()
        await PocketDiagnostics.shared.configureForTesting(
            dsn: "https://0123456789abcdef0123456789abcdef@diagnostic.invalid/1",
            session: URLSession(configuration: config))
        let user = User(userId: "USER_SENTINEL")
        user.email = "EMAIL_SENTINEL@example.invalid"
        SentrySDK.setUser(user)
        SentrySDK.configureScope { scope in
            scope.setTag(value: "TOKEN_SENTINEL", key: "private_tag")
            scope.setContext(value: ["path": "/Users/PATH_SENTINEL"], key: "private_context")
        }
        let json = """
        {"eventId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","traceId":"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb","path":"HISTORY_READ","kind":"ERROR","stage":"READ","code":"READ_FAILED","release":"ios@test","environment":"STAGING","occurredAtMs":1800000000000,"attempt":0,"suppressedCount":0,"metrics":{"resultQuality":"PARTIAL","returnedCount":3},"exception":{"type":"IllegalStateException","frames":[{"symbol":"dev.ccpocket.daemon.disk.TranscriptReplay.read","file":"TranscriptReplay.kt","line":42}]},"steps":[],"message":"PROMPT_SENTINEL","cause":"CAUSE_SENTINEL"}
        """
        XCTAssertTrue(PocketDiagnostics.shared.enqueue(json))
        XCTAssertTrue(PocketDiagnostics.shared.enqueue(json.replacingOccurrences(of: "\"kind\":\"ERROR\"", with: "\"kind\":\"LOG\"")))
        PocketDiagnostics.shared.flushForTesting()
        let envelopes = try EnvelopeProtocol.payloads().map(Self.decodeBody)
        let body = envelopes.joined(separator: "\n")
        XCTAssertGreaterThanOrEqual(envelopes.count, 1)
        XCTAssertTrue(body.contains("TranscriptReplay.kt"), body)
        XCTAssertTrue(body.contains("EP-11"), body)
        XCTAssertTrue(body.contains("log"), body)
        for envelope in envelopes {
            XCTAssertTrue(envelope.contains("returned_count"), envelope)
            XCTAssertTrue(envelope.contains("result_quality"), envelope)
            XCTAssertTrue(envelope.contains("suppressed_count"), envelope)
            XCTAssertFalse(envelope.contains("returnedCount"), envelope)
            XCTAssertFalse(envelope.contains("resultQuality"), envelope)
        }
        for sentinel in ["USER_SENTINEL", "EMAIL_SENTINEL", "TOKEN_SENTINEL", "PATH_SENTINEL", "PROMPT_SENTINEL", "CAUSE_SENTINEL", "ip_address", "server_name"] {
            XCTAssertFalse(body.contains(sentinel), sentinel)
        }
        PocketDiagnostics.shared.stopForTesting()
        XCTAssertFalse(PocketDiagnostics.shared.enqueue(json))
    }

    func testStoppingDuringAdmissionDropsLateWorkerBeforeTheSDK() async {
        EnvelopeProtocol.reset()
        let entered = expectation(description: "worker entered admission")
        let gate = DispatchSemaphore(value: 0)
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [EnvelopeProtocol.self]
        await PocketDiagnostics.shared.configureForTesting(
            dsn: "https://0123456789abcdef0123456789abcdef@diagnostic.invalid/1",
            session: URLSession(configuration: configuration), admission: { _ in
                entered.fulfill()
                _ = gate.wait(timeout: .now() + 5)
                return true
            })
        let json = """
        {"eventId":"cccccccccccccccccccccccccccccccc","path":"DIAGNOSTICS","kind":"ERROR","stage":"START","code":"UNEXPECTED","release":"ios@test","environment":"STAGING","occurredAtMs":1800000000000}
        """
        XCTAssertTrue(PocketDiagnostics.shared.enqueue(json))
        await fulfillment(of: [entered], timeout: 2)
        PocketDiagnostics.shared.stopForTesting()
        gate.signal()
        PocketDiagnostics.shared.flushForTesting()
        XCTAssertEqual(EnvelopeProtocol.payloads().count, 0)
    }

    func testManualTransactionsStripAmbientIdentityAndUseTheRequestedTrace() async throws {
        EnvelopeProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [EnvelopeProtocol.self]
        await PocketDiagnostics.shared.configureForTesting(
            dsn: "https://0123456789abcdef0123456789abcdef@diagnostic.invalid/1",
            session: URLSession(configuration: configuration))
        SentrySDK.setUser(User(userId: "TRACE_USER_SENTINEL"))
        SentrySDK.configureScope { scope in
            scope.setTag(value: "TRACE_TOKEN_SENTINEL", key: "private_tag")
            scope.setContext(value: ["path": "/Users/TRACE_PATH_SENTINEL"], key: "private_context")
        }
        let json = """
        {"eventId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","traceId":"00000064000000000000000000000001","spanId":"1234567890abcdef","parentSpanId":"fedcba0987654321","path":"SESSION_OPEN","kind":"RESULT","outcome":"SUCCESS","stage":"LAYOUT","code":"OK","release":"ios@test","environment":"STAGING","occurredAtMs":1800000000000,"elapsedMs":1000,"attempt":0,"suppressedCount":0,"steps":[{"stage":"READ","elapsedMs":0,"code":"OK"},{"stage":"LAYOUT","elapsedMs":900,"code":"OK"}]}
        """
        XCTAssertTrue(PocketDiagnostics.shared.enqueue(json))
        PocketDiagnostics.shared.flushForTesting()
        let body = try EnvelopeProtocol.payloads().map(Self.decodeBody).joined(separator: "\n")
        XCTAssertTrue(body.contains("\"transaction\""), body)
        XCTAssertTrue(body.contains("00000064000000000000000000000001"), body)
        XCTAssertTrue(body.contains("fedcba0987654321"), body)
        for sentinel in ["TRACE_USER_SENTINEL", "TRACE_TOKEN_SENTINEL", "TRACE_PATH_SENTINEL", "ip_address", "server_name"] {
            XCTAssertFalse(body.contains(sentinel), sentinel)
        }
    }

    func testUnknownResultsDoNotCreateSuccessfulManualTransactions() async throws {
        EnvelopeProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [EnvelopeProtocol.self]
        await PocketDiagnostics.shared.configureForTesting(
            dsn: "https://0123456789abcdef0123456789abcdef@diagnostic.invalid/1",
            session: URLSession(configuration: configuration))
        let json = """
        {"eventId":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa","traceId":"00000064000000000000000000000001","spanId":"1234567890abcdef","path":"SESSION_OPEN","kind":"RESULT","outcome":"UNKNOWN","stage":"APPLY","code":"INCOMPLETE","release":"ios@test","environment":"STAGING","occurredAtMs":1800000000000,"elapsedMs":1000,"attempt":0,"suppressedCount":0,"steps":[]}
        """
        XCTAssertTrue(PocketDiagnostics.shared.enqueue(json))
        PocketDiagnostics.shared.flushForTesting()
        let body = try EnvelopeProtocol.payloads().map(Self.decodeBody).joined(separator: "\n")
        XCTAssertTrue(body.contains("incomplete"), body)
        XCTAssertFalse(body.contains("\"transaction\""), body)
    }

    func testOptOutBeforeSDKInitializationCannotEnableOrUploadLate() async {
        EnvelopeProtocol.reset()
        let configuration = URLSessionConfiguration.ephemeral
        configuration.protocolClasses = [EnvelopeProtocol.self]
        await PocketDiagnostics.shared.configureForTesting(
            dsn: "https://0123456789abcdef0123456789abcdef@diagnostic.invalid/1",
            session: URLSession(configuration: configuration), disableBeforeInitialization: true)
        let json = """
        {"eventId":"dddddddddddddddddddddddddddddddd","path":"DIAGNOSTICS","kind":"ERROR","stage":"START","code":"UNEXPECTED","release":"ios@test","environment":"STAGING","occurredAtMs":1800000000000}
        """
        XCTAssertFalse(SentrySDK.isEnabled)
        XCTAssertFalse(PocketDiagnostics.shared.enqueue(json))
        PocketDiagnostics.shared.flushForTesting()
        XCTAssertTrue(EnvelopeProtocol.payloads().isEmpty)
    }

    private static func decodeBody(_ data: Data) throws -> String {
        guard data.starts(with: [0x1f, 0x8b]) else { return String(decoding: data, as: UTF8.self) }
        var stream = z_stream()
        guard inflateInit2_(&stream, MAX_WBITS + 16, ZLIB_VERSION, Int32(MemoryLayout<z_stream>.size)) == Z_OK else {
            throw NSError(domain: "inflate", code: 1)
        }
        defer { inflateEnd(&stream) }
        return try data.withUnsafeBytes { input in
            stream.next_in = UnsafeMutablePointer(mutating: input.bindMemory(to: Bytef.self).baseAddress!)
            stream.avail_in = uInt(data.count)
            let buffer = UnsafeMutablePointer<Bytef>.allocate(capacity: 8192)
            defer { buffer.deallocate() }
            var output = Data()
            var status: Int32 = Z_OK
            repeat {
                stream.next_out = buffer
                stream.avail_out = 8192
                status = inflate(&stream, Z_NO_FLUSH)
                output.append(buffer, count: 8192 - Int(stream.avail_out))
            } while status == Z_OK
            guard status == Z_STREAM_END else { throw NSError(domain: "inflate", code: Int(status)) }
            return String(decoding: output, as: UTF8.self)
        }
    }
}

private final class EnvelopeProtocol: URLProtocol {
    private static let lock = NSLock()
    private static var bodies: [Data] = []
    static func reset() { lock.lock(); defer { lock.unlock() }; bodies = [] }
    static func payloads() -> [Data] { lock.lock(); defer { lock.unlock() }; return bodies }
    override class func canInit(with request: URLRequest) -> Bool { request.url?.host == "diagnostic.invalid" }
    override class func canonicalRequest(for request: URLRequest) -> URLRequest { request }
    override func startLoading() {
        var data = request.httpBody ?? Data()
        if let stream = request.httpBodyStream {
            stream.open(); defer { stream.close() }
            var buffer = [UInt8](repeating: 0, count: 8192)
            while true {
                let count = stream.read(&buffer, maxLength: buffer.count)
                if count <= 0 { break }
                data.append(buffer, count: count)
            }
        }
        Self.lock.lock(); Self.bodies.append(data); Self.lock.unlock()
        let response = HTTPURLResponse(url: request.url!, statusCode: 200, httpVersion: nil, headerFields: nil)!
        client?.urlProtocol(self, didReceive: response, cacheStoragePolicy: .notAllowed)
        client?.urlProtocol(self, didLoad: Data("{}".utf8))
        client?.urlProtocolDidFinishLoading(self)
    }
    override func stopLoading() {}
}
