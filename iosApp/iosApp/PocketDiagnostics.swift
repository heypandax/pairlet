import Foundation
import ComposeApp
import Sentry

/// Explicit safe records only. Crashlytics remains the native crash owner during rollout.
final class PocketDiagnostics {
    static let shared = PocketDiagnostics()
    private let lock = NSRecursiveLock()
    private let queue = DispatchQueue(label: "cc-pocket.diagnostics", qos: .utility)
    private var enabled = false
    private var generation = 0
    private var queued = 0
    private var day: Int64 = -1
    private var bytesToday = 0
    private var pending: [String: Record] = [:]
    private var pendingTraces: [String: Record] = [:]
    private var admitTrace: (() -> Bool)?
    private var session: URLSession?
    private var cache: URL?
    private var dsn: String?
    private var admit: ((String) -> Bool)?
    private var healthCounter: ((String) -> Void)?

    func register() {
        admit = { MainViewControllerKt.admitDiagnosticRecord(json: $0) }
        admitTrace = { MainViewControllerKt.admitDiagnosticSpans() }
        healthCounter = { MainViewControllerKt.incrementDiagnosticCounter(name: $0) }
        let info = Bundle.main.infoDictionary ?? [:]
        // A public DSN can be supplied via Info.plist for an archive or launch environment for QA.
        dsn = ProcessInfo.processInfo.environment["CCPOCKET_SENTRY_DSN_IOS"] ?? info["CCPocketSentryDSN"] as? String
        let environment = ProcessInfo.processInfo.environment["CCPOCKET_SENTRY_ENVIRONMENT"] ?? info["CCPocketSentryEnvironment"] as? String ?? "unknown"
        MainViewControllerKt.setDiagnosticSink(environment: environment, onRecord: { [weak self] json in
            KotlinBoolean(bool: self?.enqueue(json) ?? false)
        }, onEnabled: { [weak self] value in self?.configure(value.boolValue) })
    }

    private func configure(_ value: Bool, suppliedSession: URLSession? = nil, completion: (() -> Void)? = nil) {
        lock.lock()
        enabled = false
        generation += 1
        let token = generation
        session?.invalidateAndCancel()
        session = nil
        lock.unlock()
        // Cocoa initializes UIKit dependencies and its hub on main. Serialize start/close there;
        // our cache sweep, budget writes and record processing remain on the diagnostic worker.
        DispatchQueue.main.async { [self] in
            lock.lock()
            guard token == generation else { lock.unlock(); completion?(); return }
            let hadSDK = cache != nil
            pending.removeAll(); pendingTraces.removeAll(); cache = nil
            lock.unlock()
            if hadSDK { SentrySDK.close() }
            queue.async { [self] in prepareOnWorker(value, suppliedSession: suppliedSession, token: token, completion: completion) }
        }
    }

    private func prepareOnWorker(_ value: Bool, suppliedSession: URLSession?, token: Int, completion: (() -> Void)?) {
        lock.lock()
        guard token == generation else { lock.unlock(); completion?(); return }
        lock.unlock()
        // Previous strong kills can leave an isolated SDK directory. Delete it BEFORE starting a new
        // SDK; never replay it across opt-out/reopen. A cleanup failure leaves this collector disabled.
        guard discardAbandonedCaches() else { healthCounter?("STORAGE_FAILED"); completion?(); return }
        guard value, let dsn, Self.validDSN(dsn) else { completion?(); return }
        let transportSession = suppliedSession ?? DiagnosticURLSession { [weak self] in self?.healthCounter?($0) }
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("cc-pocket-diagnostics-\(UUID().uuidString)")
        DispatchQueue.main.async { [self] in
            startOnMain(dsn: dsn, transportSession: transportSession, directory: directory, token: token)
            completion?()
        }
    }

    private func startOnMain(dsn: String, transportSession: URLSession, directory: URL, token: Int) {
        lock.lock()
        guard token == generation else { lock.unlock(); transportSession.invalidateAndCancel(); return }
        lock.unlock()
        SentrySDK.start { options in
            options.dsn = dsn
            options.sendDefaultPii = false
            options.enableCrashHandler = false
            options.enableAutoSessionTracking = false
            options.enableAutoPerformanceTracing = false
            options.enableSwizzling = false
            options.enableAppHangTracking = false
            options.enableAutoBreadcrumbTracking = false
            options.enableNetworkTracking = false
            options.enableCaptureFailedRequests = false
            options.attachStacktrace = false
            options.attachScreenshot = false
            options.attachViewHierarchy = false
            options.sendClientReports = false
            options.tracesSampleRate = 0
            options.tracesSampler = { [weak self] context in
                guard let self else { return 0 }
                self.lock.lock(); defer { self.lock.unlock() }
                return self.enabled && self.pendingTraces[context.transactionContext.spanId.sentrySpanIdString] != nil ? 1 : 0
            }
            options.beforeSendSpan = { [weak self] span in
                guard let self else { return nil }
                self.lock.lock(); defer { self.lock.unlock() }
                guard self.enabled, let parent = span.parentSpanId,
                      let record = self.pendingTraces[parent.sentrySpanIdString],
                      record.traceId == span.traceId.sentryIdString else { return nil }
                for key in span.data.keys { span.removeData(key: key) }
                for key in span.tags.keys { span.removeTag(key: key) }
                span.spanDescription = nil
                return span
            }
            options.integrations = []
            options.maxCacheItems = 16
            options.cacheDirectoryPath = directory.path
            options.urlSession = transportSession
            options.shutdownTimeInterval = 0
            options.experimental.enableLogs = true
            options.beforeSend = { [weak self] enriched in
                guard let self else { return nil }
                self.lock.lock(); defer { self.lock.unlock() }
                guard self.enabled else { return nil }
                if enriched.type == "transaction" {
                    // In Cocoa 8.58 the transaction's root trace is materialized by the public
                    // serializer, not Event.context. Extract only IDs; never forward this dictionary.
                    let contexts = enriched.serialize()["contexts"] as? [String: Any]
                    let trace = contexts?["trace"] as? [String: Any]
                    guard let spanId = trace?["span_id"] as? String,
                          let record = self.pendingTraces[spanId],
                          record.traceId == trace?["trace_id"] as? String else { return nil }
                    self.pendingTraces.removeValue(forKey: spanId)
                    enriched.context = ["trace": self.traceContext(record)]
                    enriched.tags = self.tags(record)
                    enriched.transaction = record.path.lowercased()
                    enriched.releaseName = record.release
                    enriched.environment = record.environment.lowercased()
                    enriched.user = nil; enriched.request = nil; enriched.extra = nil
                    enriched.serverName = nil; enriched.threads = nil; enriched.debugMeta = nil
                    enriched.breadcrumbs = []; enriched.modules = nil; enriched.sdk = nil
                    enriched.message = nil; enriched.exceptions = nil; enriched.fingerprint = nil
                    return enriched
                }
                guard let record = self.pending.removeValue(forKey: enriched.eventId.sentryIdString) else { return nil }
                // Reconstruct after SDK enrichment. No device/user/request/threads/debugMeta/extra survive.
                return self.event(record)
            }
            options.beforeSendLog = { [weak self] log in
                guard let self else { return nil }
                self.lock.lock(); defer { self.lock.unlock() }
                guard self.enabled, log.attributes["diag_event_id"] != nil else { return nil }
                log.attributes = log.attributes.filter { Self.logKeys.contains($0.key) }
                if let id = log.attributes["diag_trace_id"]?.value as? String ?? log.attributes["diag_event_id"]?.value as? String {
                    log.traceId = SentryId(uuidString: id)
                }
                return log
            }
        }
        lock.lock()
        if token == generation {
            session = transportSession
            cache = directory
            enabled = SentrySDK.isEnabled
            lock.unlock()
        } else {
            lock.unlock()
            transportSession.invalidateAndCancel()
            SentrySDK.close()
            queue.async { try? FileManager.default.removeItem(at: directory) }
        }
    }

    private func traceContext(_ r: Record) -> [String: Any] {
        var result: [String: Any] = ["trace_id": r.traceId ?? r.eventId,
                                    "span_id": r.spanId ?? String(r.eventId.prefix(16)),
                                    "op": r.path.lowercased(),
                                    "status": ["FAILURE", "TIMEOUT"].contains(r.outcome ?? "") ? "internal_error" : "ok"]
        result["parent_span_id"] = r.parentSpanId
        return result
    }

    private func captureManualTrace(_ record: Record, generation token: Int) {
        guard ["CONNECTION", "PAIRING", "SESSION_OPEN", "PROMPT"].contains(record.path),
              ["SUCCESS", "FAILURE", "TIMEOUT"].contains(record.outcome ?? ""),
              ["RESULT", "ERROR"].contains(record.kind), let id = record.traceId, let spanId = record.spanId,
              let sample = UInt64(id.prefix(8), radix: 16), sample % 100 == 0,
              let duration = record.elapsedMs else { return }
        guard admitTrace?() ?? true else { return }
        lock.lock()
        guard enabled, token == generation, bytesToday + 16_384 <= 1_048_576 else { lock.unlock(); return }
        bytesToday += 16_384
        if pendingTraces.count >= 16, let oldest = pendingTraces.keys.first { pendingTraces.removeValue(forKey: oldest) }
        pendingTraces[spanId] = record
        lock.unlock()
        let context = TransactionContext(name: record.path.lowercased(), operation: record.path.lowercased(),
            trace: SentryId(uuidString: id), spanId: SpanId(value: spanId),
            parentSpanId: record.parentSpanId.map { SpanId(value: $0) }, parentSampled: .yes,
            parentSampleRate: 0.01, parentSampleRand: nil)
        let transaction = SentrySDK.startTransaction(transactionContext: context, bindToScope: false)
        for key in transaction.data.keys { transaction.removeData(key: key) }
        for key in transaction.tags.keys { transaction.removeTag(key: key) }
        transaction.spanDescription = nil
        let elapsed = min(max(duration, 0), 86_400_000)
        let start = max(record.occurredAtMs - elapsed, 0)
        transaction.startTimestamp = Date(timeIntervalSince1970: Double(start) / 1000)
        let steps = Array((record.steps ?? []).suffix(15))
        for (index, step) in steps.enumerated() {
            let from = start + min(max(step.elapsedMs, 0), elapsed)
            let until = index + 1 < steps.count ? start + min(max(steps[index + 1].elapsedMs, 0), elapsed) : record.occurredAtMs
            let child = transaction.startChild(operation: step.stage.lowercased())
            child.startTimestamp = Date(timeIntervalSince1970: Double(from) / 1000)
            child.timestamp = Date(timeIntervalSince1970: Double(max(from, until)) / 1000)
            child.finish(status: .ok)
        }
        transaction.timestamp = Date(timeIntervalSince1970: Double(record.occurredAtMs) / 1000)
        transaction.finish(status: ["FAILURE", "TIMEOUT"].contains(record.outcome ?? "") ? .internalError : .ok)
    }

    private func discardAbandonedCaches() -> Bool {
        let manager = FileManager.default
        do {
            let paths = try manager.contentsOfDirectory(at: manager.temporaryDirectory, includingPropertiesForKeys: nil)
            for path in paths where path.lastPathComponent.hasPrefix("cc-pocket-diagnostics-") {
                try manager.removeItem(at: path)
            }
            return true
        } catch { return false }
    }

    func enqueue(_ json: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        let size = json.utf8.count
        let today = Int64(Date().timeIntervalSince1970) / 86_400
        if today > day { day = today; bytesToday = 0 }
        guard enabled else { healthCounter?("CLOSED_DROPPED"); return false }
        guard queued < 16 else { healthCounter?("QUEUE_DROPPED"); return false }
        guard size <= 16_384, bytesToday + size <= 1_048_576 else { healthCounter?("BUDGET_REJECTED"); return false }
        let token = generation
        queued += 1
        queue.async { [weak self] in
            guard let self else { return }
            defer { self.lock.lock(); self.queued -= 1; self.lock.unlock() }
            self.lock.lock()
            let current = self.enabled && self.generation == token
            self.lock.unlock()
            guard current else { self.healthCounter?("CLOSED_DROPPED"); return }
            guard
                  let data = json.data(using: .utf8), let record = try? JSONDecoder().decode(Record.self, from: data),
                  record.isValid else { self.healthCounter?("SDK_FILTERED"); return }
            // Persist a bounded aggregate reservation on the worker, outside the UI/opt-out lock.
            guard self.admit?(json) ?? true else { return }
            self.lock.lock()
            guard self.enabled, self.generation == token else { self.lock.unlock(); self.healthCounter?("CLOSED_DROPPED"); return }
            self.bytesToday += size
            if record.kind == "ERROR" { self.pending[record.eventId] = record }
            self.lock.unlock()
            if record.kind == "ERROR" {
                SentrySDK.capture(event: self.event(record))
                self.lock.lock(); self.pending.removeValue(forKey: record.eventId); self.lock.unlock()
            } else {
                var attributes: [String: Any] = self.tags(record)
                attributes["release"] = record.release
                attributes["environment"] = record.environment.lowercased()
                attributes["attempt"] = record.attempt ?? 0
                attributes["elapsed_ms"] = record.elapsedMs
                attributes["suppressed_count"] = record.suppressedCount ?? 0
                self.metricAttributes(record).forEach { attributes[$0.key] = $0.value }
                SentrySDK.logger.info("\(record.pathID):\(record.code.lowercased())", attributes: attributes)
            }
            self.captureManualTrace(record, generation: token)
        }
        return true
    }

    #if DEBUG
    @MainActor
    func configureForTesting(dsn: String, session: URLSession, admission: ((String) -> Bool)? = nil,
                             disableBeforeInitialization: Bool = false) async {
        self.dsn = dsn
        self.admit = admission
        self.admitTrace = nil
        self.healthCounter = nil
        await withCheckedContinuation { continuation in
            if disableBeforeInitialization {
                // Both requests are queued before main can initialize the SDK, matching a rapid opt-out.
                configure(true, suppliedSession: session)
                configure(false) { continuation.resume() }
            } else {
                configure(true, suppliedSession: session) { continuation.resume() }
            }
        }
    }
    func flushForTesting() { queue.sync {}; SentrySDK.flush(timeout: 2) }
    func stopForTesting() { configure(false) }
    #endif

    private func tags(_ r: Record) -> [String: String] {
        var result = ["diag_schema": "1", "diag_event_id": r.eventId, "error_path": r.pathID,
                      "operation": r.path.lowercased(), "stage": r.stage.lowercased(), "code": r.code.lowercased(),
                      "component": "ios", "coverage": "client_only"]
        result["connection_id"] = r.connectionId
        result["peer_connection_id"] = r.peerConnectionId
        result["diag_trace_id"] = r.traceId
        result["diag_span_id"] = r.spanId
        result["outcome"] = r.outcome?.lowercased()
        result["exception_type"] = r.exception?.type
        result["agent"] = r.metrics?["backend"]?.value as? String
        return result
    }

    private func event(_ r: Record) -> Event {
        let result = Event(level: .error)
        result.eventId = SentryId(uuidString: r.eventId)
        result.timestamp = Date(timeIntervalSince1970: Double(r.occurredAtMs) / 1000)
        result.releaseName = r.release
        result.environment = r.environment.lowercased()
        result.platform = "cocoa"
        result.logger = "cc-pocket.diagnostics"
        result.message = SentryMessage(formatted: "\(r.pathID):\(r.code.lowercased())")
        result.tags = tags(r)
        var context: [String: Any] = ["attempt": r.attempt ?? 0, "suppressed_count": r.suppressedCount ?? 0]
        context["elapsed_ms"] = r.elapsedMs
        context["steps"] = r.steps?.map { ["stage": $0.stage.lowercased(), "code": $0.code.lowercased(), "elapsed_ms": $0.elapsedMs] as [String: Any] }
        metricAttributes(r).forEach { context[$0.key] = $0.value }
        result.context = ["diagnostic": context]
        if r.traceId != nil && r.spanId != nil { result.context?["trace"] = traceContext(r) }
        if let error = r.exception {
            let exception = Exception(value: r.code.lowercased(), type: error.type)
            exception.stacktrace = SentryStacktrace(frames: error.frames.reversed().map { safe in
                let frame = Frame()
                frame.function = safe.symbol
                frame.fileName = safe.file
                frame.lineNumber = safe.line.map { NSNumber(value: $0) }
                frame.inApp = true
                return frame
            }, registers: [:])
            result.exceptions = [exception]
        }
        result.fingerprint = ["IOS", r.pathID, r.stage, r.code, r.exception?.frames.first?.symbol ?? r.exception?.type ?? "no_stack"]
        return result
    }

    private static func validDSN(_ value: String) -> Bool {
        guard value.count <= 512, let url = URLComponents(string: value), url.scheme == "https", url.host?.isEmpty == false,
              let user = url.user, user.range(of: "^[a-fA-F0-9]{16,64}$", options: .regularExpression) != nil,
              url.password == nil, url.query == nil, url.fragment == nil else { return false }
        return url.path.range(of: "^/[0-9]+$", options: .regularExpression) != nil
    }

    private static let paths = ["STARTUP", "ASYNC_WORKER", "CONNECTION", "PAIRING", "HANDSHAKE", "PROTOCOL", "OUTBOX", "RELAY", "SESSION_LIST", "SESSION_OPEN", "HISTORY_READ", "HISTORY_PAGE", "PAYLOAD_SEND", "HISTORY_APPLY", "CONTENT", "AGENT_START", "PROMPT", "AGENT_PROTOCOL", "TURN", "APPROVAL", "FILE_READ", "FILE_UPLOAD", "STORAGE", "BACKGROUND", "PEER_DELIVERY", "GIT", "UPDATE", "PUSH", "LIFECYCLE", "DIAGNOSTICS"]
    // Keep cloud query keys identical to the Java adapter; Kotlin's JSON property names stay internal.
    private static let metricKeys = ["totalCount": "total_count", "failedCount": "failed_count",
                                     "returnedCount": "returned_count", "byteCount": "byte_count",
                                     "queueSize": "queue_size", "exitCode": "exit_code",
                                     "resultQuality": "result_quality", "transport": "transport"]
    private static let logKeys: Set<String> = ["diag_schema", "diag_event_id", "diag_trace_id", "diag_span_id", "connection_id", "peer_connection_id", "error_path", "operation", "stage", "code", "component", "coverage", "outcome", "agent", "exception_type", "release", "environment", "attempt", "elapsed_ms", "suppressed_count", "total_count", "failed_count", "returned_count", "byte_count", "queue_size", "exit_code", "result_quality", "transport"]

    private func metricAttributes(_ record: Record) -> [String: Any] {
        var result: [String: Any] = [:]
        for (source, target) in Self.metricKeys {
            if let value = record.metrics?[source]?.value { result[target] = value }
        }
        return result
    }

    /// The producer is the closed Kotlin model, never arbitrary application JSON.
    private struct Record: Decodable {
        let connectionId: String?; let peerConnectionId: String?
        let eventId: String; let traceId: String?; let spanId: String?; let parentSpanId: String?; let path: String; let kind: String
        let stage: String; let code: String; let outcome: String?; let release: String; let environment: String
        let occurredAtMs: Int64; let elapsedMs: Int64?; let attempt: Int?; let suppressedCount: Int64?
        let exception: SafeException?; let metrics: [String: Scalar]?; let steps: [Step]?
        var pathID: String { String(format: "EP-%02d", (PocketDiagnostics.paths.firstIndex(of: path) ?? 29) + 1) }
        var isValid: Bool { PocketDiagnostics.paths.contains(path) && eventId.range(of: "^[a-f0-9]{32}$", options: .regularExpression) != nil }
    }
    private struct SafeException: Decodable { let type: String; let frames: [SafeFrame] }
    private struct SafeFrame: Decodable { let symbol: String; let file: String?; let line: Int? }
    private struct Step: Decodable { let stage: String; let code: String; let elapsedMs: Int64 }
    private enum Scalar: Decodable {
        case number(Int64), string(String), absent
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() { self = .absent }
            else if let n = try? c.decode(Int64.self) { self = .number(n) }
            else { self = .string(try c.decode(String.self)) }
        }
        var value: Any? { switch self { case .number(let n): return n; case .string(let s): return s.lowercased(); case .absent: return nil } }
    }
}

/// Transport request counters have request units; they do not guess the number of items in a log batch.
private final class DiagnosticURLSession: URLSession, @unchecked Sendable {
    private let transport = URLSession(configuration: .ephemeral)
    private let record: (String) -> Void
    init(record: @escaping (String) -> Void) { self.record = record; super.init() }
    override func dataTask(with request: URLRequest,
        completionHandler: @escaping @Sendable (Data?, URLResponse?, Error?) -> Void) -> URLSessionDataTask {
        // Cocoa SDK uses completion-handler tasks. Observe that callback directly rather than assuming
        // delegate completion callbacks are delivered for this task family. SDK still owns retries.
        transport.dataTask(with: request) { [record] data, response, error in
            let cancelled = (error as NSError?)?.domain == NSURLErrorDomain && (error as NSError?)?.code == NSURLErrorCancelled
            if !cancelled {
                if (response as? HTTPURLResponse)?.statusCode == 429 { record("TRANSPORT_RATE_LIMITED") }
                else if error != nil || ((response as? HTTPURLResponse)?.statusCode ?? 0) >= 400 { record("TRANSPORT_FAILED") }
            }
            completionHandler(data, response, error)
        }
    }
    override func invalidateAndCancel() { transport.invalidateAndCancel() }
    override func finishTasksAndInvalidate() { transport.finishTasksAndInvalidate() }
}
