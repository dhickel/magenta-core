-- Phase 03: one durable, race-safe submission decision precedes immutable
-- context capture, assignment insertion, and lifecycle reservation.
create table if not exists assignment_binding_operations (
    operation_key   text primary key check (length(operation_key) between 1 and 255),
    request_digest  text not null check (
        length(request_digest) = 64 and request_digest not glob '*[^0-9a-f]*'),
    operation_state text not null check (operation_state in ('RESERVED', 'COMPLETED')),
    binding_key     text,
    assignment_id   text,
    task_run_id     text,
    created_at      text not null,
    updated_at      text not null,
    check ((operation_state = 'RESERVED'
            and binding_key is null and assignment_id is null and task_run_id is null)
        or (operation_state = 'COMPLETED'
            and binding_key is not null and assignment_id is not null))
);

create index if not exists idx_assignment_binding_operations_assignment
    on assignment_binding_operations (assignment_id)
    where assignment_id is not null;
