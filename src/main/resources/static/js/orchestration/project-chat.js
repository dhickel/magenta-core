/** Restricted project-chat SSE transport. Provider text is inert DOM text only. */
;(function () {
  'use strict';
  var MAX_MESSAGE_LENGTH = 16000;
  var MAX_EVENT_PAYLOAD = 128000;
  var PANEL_ID = 'project-chat-panel';
  var HISTORY_ID = 'project-chat-history';
  var active = null;

  function panel() { return document.getElementById(PANEL_ID); }
  function history() { return document.getElementById(HISTORY_ID); }
  function input() { return document.getElementById('project-chat-input'); }
  function status() { var host = panel(); return host && host.querySelector('.project-chat-stream-status'); }
  function setStatus(text) { var host = status(); if (host) host.textContent = text || ''; }
  function setLoading(value) {
    var host = panel(); if (!host) return;
    var send = host.querySelector('#project-chat-send-btn');
    var cancel = host.querySelector('#project-chat-cancel-btn');
    var field = input();
    if (send) send.hidden = value;
    if (cancel) cancel.hidden = !value;
    if (field) field.disabled = value;
  }
  function reset(clearStatus, expected) {
    if (expected && active !== expected) return;
    if (active) active.controller.abort();
    active = null;
    setLoading(false);
    if (clearStatus) setStatus('');
  }
  function appendMessage(role, text) {
    var host = history(); if (!host) return;
    var item = document.createElement('div');
    item.className = 'project-chat-msg ' + (role === 'user' ? 'project-chat-msg-user' : 'project-chat-msg-assistant');
    var label = document.createElement('div');
    label.className = 'project-chat-msg-role';
    label.textContent = role === 'user' ? 'You' : 'Assistant';
    var body = document.createElement('div');
    body.className = 'project-chat-msg-text';
    body.textContent = text || '';
    item.append(label, body);
    host.appendChild(item);
    host.scrollTop = host.scrollHeight;
  }
  function parseEvent(block) {
    if (block.length > MAX_EVENT_PAYLOAD) return null;
    var name = '', lines = [];
    block.replace(/\r/g, '').split('\n').forEach(function (line) {
      if (line.indexOf('event:') === 0) name = line.slice(6).trim();
      else if (line.indexOf('data:') === 0) lines.push(line.slice(5).trimStart());
    });
    if (!/^(start|message|done|error)$/.test(name)) return null;
    var payload = lines.join('\n');
    if (payload.length > MAX_EVENT_PAYLOAD) return null;
    try { return { name: name, data: JSON.parse(payload) }; } catch (_) { return null; }
  }
  function exactIdentity(data, state) {
    return data && data.conversationId === state.conversationId && data.operationId === state.operationId;
  }
  function handle(event, state) {
    if (active !== state || panel() !== state.panel) return false;
    var data = event.data;
    if (event.name === 'start') {
      if (!data || data.projectId !== state.projectId || data.memberAgentId !== state.agentId
          || data.conversationId !== state.conversationId || typeof data.operationId !== 'string'
          || data.operationId.length > 128) return false;
      state.operationId = data.operationId;
      setStatus('Assistant is thinking…');
      return true;
    }
    if (!state.operationId || !exactIdentity(data, state)) return false;
    if (event.name === 'message') {
      if (data.role !== 'assistant' || typeof data.text !== 'string' || data.text.length > MAX_EVENT_PAYLOAD) return false;
      appendMessage('assistant', data.text);
      setStatus('');
      return true;
    }
    if (event.name === 'done') { reset(true, state); return false; }
    if (event.name === 'error') { setStatus('Project chat is unavailable.'); reset(false, state); return false; }
    return false;
  }
  async function stream(state, message) {
    var controller = state.controller;
    try {
      var response = await fetch('/api/projects/' + encodeURIComponent(state.projectId) + '/chat/stream', {
        method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ message: message, memberAgentId: state.agentId }), signal: controller.signal
      });
      if (!response.ok || !response.body) { setStatus('Project chat is unavailable.'); reset(false, state); return; }
      var reader = response.body.getReader(), decoder = new TextDecoder(), buffer = '';
      while (!controller.signal.aborted) {
        var result = await reader.read();
        if (result.done) break;
        buffer += decoder.decode(result.value, { stream: true });
        if (buffer.length > MAX_EVENT_PAYLOAD) { setStatus('Stream payload exceeded the safety limit.'); reset(false, state); return; }
        var match;
        while ((match = /\r?\n\r?\n/.exec(buffer)) !== null) {
          var parsed = parseEvent(buffer.slice(0, match.index));
          buffer = buffer.slice(match.index + match[0].length);
          if (!parsed || !handle(parsed, state)) return;
        }
      }
      if (!controller.signal.aborted) reset(true, state);
    } catch (error) {
      if (error.name !== 'AbortError' && active === state) setStatus('Project chat stream failed.');
      reset(error.name === 'AbortError', state);
    }
  }
  function send(host) {
    if (active || host !== panel()) return;
    var field = input(), message = field && field.value.trim();
    var projectId = host.getAttribute('data-project-chat-project');
    var agentId = host.getAttribute('data-project-chat-agent');
    var conversationId = host.getAttribute('data-project-chat-conversation');
    if (!projectId || !agentId || !conversationId || !message || message.length > MAX_MESSAGE_LENGTH) return;
    appendMessage('user', message);
    field.value = '';
    setLoading(true);
    active = { controller: new AbortController(), projectId: projectId, agentId: agentId,
      conversationId: conversationId, operationId: null, panel: host };
    stream(active, message);
  }
  document.addEventListener('click', function (event) {
    var host = panel(); if (!host) return;
    if (event.target.closest('#project-chat-send-btn')) { event.preventDefault(); send(host); }
    if (event.target.closest('#project-chat-cancel-btn')) { event.preventDefault(); reset(true); }
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Enter' && event.target === input() && !event.shiftKey) {
      event.preventDefault(); var host = panel(); if (host) send(host);
    }
  });
  document.body.addEventListener('htmx:beforeSwap', function (event) {
    var target = event.detail && event.detail.target;
    if (active && target && (target === active.panel || target.contains(active.panel))) reset(true);
  });
})();
