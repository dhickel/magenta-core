package io.mindspice.magenta.core;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;

/**
 * Identifies how an execution was started without representing origin IDs as
 * nullable fields.
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
    @JsonSubTypes.Type(value = ExecutionOrigin.Direct.class, name = "direct"),
    @JsonSubTypes.Type(value = ExecutionOrigin.Answer.class, name = "answer"),
    @JsonSubTypes.Type(value = ExecutionOrigin.Queued.class, name = "queued")
})
public sealed interface ExecutionOrigin
    permits ExecutionOrigin.Direct, ExecutionOrigin.Answer, ExecutionOrigin.Queued {

    /** A normal caller-submitted execution. */
    record Direct() implements ExecutionOrigin {
    }

    /** A continuation started by answering one durable interaction. */
    record Answer(@NonNull String interactionId) implements ExecutionOrigin {
        public Answer {
            interactionId = ResponseValidation.nonBlank(interactionId, "interactionId");
        }
    }

    /** An execution claimed internally from the durable queue. */
    record Queued(@NonNull String messageId) implements ExecutionOrigin {
        public Queued {
            messageId = ResponseValidation.nonBlank(messageId, "messageId");
        }
    }
}
