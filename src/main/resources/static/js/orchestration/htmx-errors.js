/**
 * Permits a declared operational fragment to render its own marked non-2xx
 * response. This changes neither transport nor response status.
 */
;(function () {
  'use strict';

  var RESPONSE_MARKER = /(?:class=["'][^"']*(?:orch-error|orch-status-error|agent-context-panel|system-chat-panel|system-chat-proposals|workspace-file-status-error)[^"']*["'])/i;

  function isSameOrigin(path) {
    try {
      return new URL(path, window.location.href).origin === window.location.origin;
    } catch (_) {
      return false;
    }
  }

  document.body.addEventListener('htmx:beforeSwap', function (event) {
    var detail = event.detail;
    var xhr = detail.xhr;
    var request = detail.requestConfig;
    var element = request && request.elt;
    if (!xhr || xhr.status < 300 || xhr.status === 401 || xhr.status === 403) return;
    if (!element || element.getAttribute('data-htmx-error-swap') !== 'true') return;
    if (!request || !isSameOrigin(request.path)) return;
    if (!detail.serverResponse || !RESPONSE_MARKER.test(detail.serverResponse)) return;

    detail.shouldSwap = true;
  });
})();
