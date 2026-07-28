-- Phase 05: system-chat action proposals.
-- Proposals are canonical, non-executing, and confirmed through the
-- existing ordinary scoped lifecycle control plane.
create table if not exists system_chat_action_proposals (
    id                        text primary key check (length(id) between 1 and 128),
    conversation_id           text not null check (length(conversation_id) between 1 and 255),
    agent_id                  text not null check (
        length(agent_id) > 0
        and instr(agent_id, '/') = 0 and instr(agent_id, char(92)) = 0),
    target_kind               text not null check (target_kind in ('ASSIGNMENT')),
    target_id                 text not null check (length(target_id) between 1 and 255),
    action                    text not null check (action in (
        'PAUSE', 'RESUME', 'RETRY', 'REPLAY', 'CANCEL')),
    expected_state_or_revision text,
    canonical_payload_digest  text not null check (
        length(canonical_payload_digest) = 64
        and canonical_payload_digest not glob '*[^0-9a-f]*'),
    operation_key             text not null check (length(operation_key) between 1 and 255),
    proposal_state            text not null check (proposal_state in (
        'PENDING', 'CONFIRMING', 'CONFIRMED', 'DENIED', 'CANCELLED', 'STALE', 'UNAVAILABLE')),
    confirmation_operation_key text,
    linked_operation_id       text,
    confirmed_at              text,
    audit_facts_json          text,
    created_at                text not null,
    updated_at                text not null,
    unique (operation_key),
    unique (target_kind, target_id, action, canonical_payload_digest)
);

create index if not exists idx_system_chat_proposals_conversation
    on system_chat_action_proposals (conversation_id, created_at desc);
create index if not exists idx_system_chat_proposals_agent
    on system_chat_action_proposals (agent_id, proposal_state, created_at desc);
