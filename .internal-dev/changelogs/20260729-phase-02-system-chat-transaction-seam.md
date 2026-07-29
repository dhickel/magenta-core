---
date: 2026-07-29
change_summary: >
  Phase 02 — Split SystemChatService into transactional preparation
  (SystemChatProfilePreparationService) and transaction-free inference
  (invoke) phases. Removed @Transactional from resolveProfile and invoke;
  invoke now uses Propagation.NOT_SUPPORTED. Moved all binding/origin/
  owner-link preparation into a dedicated collaborator with preflight
  validation, atomic writes, and post-write verification with rollback.
files:
  - src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatService.java
  - src/main/java/io/mindspice/magenta2/ai/chat/system/SystemChatProfilePreparationService.java (new)
  - src/test/java/io/mindspice/magenta2/ai/chat/system/SystemChatServiceTest.java
  - src/test/java/io/mindspice/magenta2/ai/chat/system/SystemChatTransactionIntegrationTest.java (new)

behavioral_impact: >
  No user-facing behavioral change. Preparation either commits the complete
  compatible binding/origin/owner-link fact set before inference or rolls
  back without model invocation; no partial binding remains after a rejected
  or raced preparation. The model callback has no active transaction, including
  when invoke is called from an ambient transaction. Fixed-profile security,
  redaction, unavailable behavior, and unconditional holder cleanup remain intact.

specification_impact: none — behavioral contracts unchanged.

risks:
  - Other tests (PublicApiRouteBindingTest, 2 failures) are pre-existing and unrelated.

follow_up_items:
  - None. Phase 02 exit criteria met.
