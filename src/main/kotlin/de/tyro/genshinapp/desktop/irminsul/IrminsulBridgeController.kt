package de.tyro.genshinapp.desktop.irminsul

import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@Profile("desktop")
@RequestMapping("/api/desktop/irminsul")
class IrminsulBridgeController(
    private val integrationService: IrminsulIntegrationService,
) {
    @GetMapping("/status")
    fun currentStatus(): IrminsulCaptureStatus = integrationService.status()

    @PostMapping("/status")
    fun status(
        @RequestHeader(TOKEN_HEADER, required = false) token: String?,
        @RequestHeader(SESSION_HEADER, required = false) session: String?,
        @RequestBody event: IrminsulStatusEvent,
    ): ResponseEntity<BridgeResponse> {
        if (!integrationService.accepts(token, session)) return unauthorized()
        integrationService.receiveStatus(event)
        return ResponseEntity.ok(BridgeResponse(ok = true))
    }

    @PostMapping("/snapshot")
    fun snapshot(
        @RequestHeader(TOKEN_HEADER, required = false) token: String?,
        @RequestHeader(SESSION_HEADER, required = false) session: String?,
        request: HttpServletRequest,
    ): ResponseEntity<BridgeResponse> {
        if (!integrationService.accepts(token, session)) return unauthorized()
        val bytes = request.inputStream.readNBytes(MAX_SNAPSHOT_SIZE + 1)
        if (bytes.size > MAX_SNAPSHOT_SIZE) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                .body(BridgeResponse(ok = false, message = "Snapshot is too large"))
        }
        val result = integrationService.receiveSnapshot(bytes)
        return if (result.state in setOf(
                IrminsulCaptureState.LIVE,
                IrminsulCaptureState.COMPLETE,
            )
        ) {
            ResponseEntity.ok(BridgeResponse(ok = true, message = result.message))
        } else {
            ResponseEntity.unprocessableEntity()
                .body(BridgeResponse(ok = false, message = result.message))
        }
    }

    @GetMapping("/control")
    fun control(
        @RequestHeader(TOKEN_HEADER, required = false) token: String?,
        @RequestHeader(SESSION_HEADER, required = false) session: String?,
    ): ResponseEntity<ControlResponse> {
        if (!integrationService.accepts(token, session)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build()
        }
        return ResponseEntity.ok(
            ControlResponse(
                cancelRequested = integrationService.cancellationRequested(requireNotNull(session)),
            ),
        )
    }

    private fun unauthorized(): ResponseEntity<BridgeResponse> =
        ResponseEntity.status(HttpStatus.UNAUTHORIZED)
            .body(BridgeResponse(ok = false, message = "Invalid desktop capture session"))

    companion object {
        private const val TOKEN_HEADER = "X-Genshin-Desktop-Token"
        private const val SESSION_HEADER = "X-Genshin-Capture-Session"
        private const val MAX_SNAPSHOT_SIZE = 5 * 1024 * 1024
    }
}

data class BridgeResponse(
    val ok: Boolean,
    val message: String? = null,
)

data class ControlResponse(
    val cancelRequested: Boolean,
)
