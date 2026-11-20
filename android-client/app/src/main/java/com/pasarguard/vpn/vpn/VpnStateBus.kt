package com.pasarguard.vpn.vpn

import com.pasarguard.vpn.domain.model.VpnSessionState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VpnStateBus @Inject constructor() {
    private val _state = MutableStateFlow(VpnSessionState())
    val state: StateFlow<VpnSessionState> = _state.asStateFlow()

    fun update(transform: (VpnSessionState) -> VpnSessionState) {
        _state.update(transform)
    }
}
