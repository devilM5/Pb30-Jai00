const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const ALICE_UID = "alice_123";
const BOB_UID = "bob_456";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

test("Unauthenticated user cannot read or write notes, chat_messages, or terminal_logs", async () => {
  const unauthDb = testEnv.unauthenticatedContext().firestore();
  const now = new Date(Date.now() - 1000);

  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("notes").get()
  );
  await assertFails(
    unauthDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .doc("note_1")
      .set({
        userId: ALICE_UID,
        title: "Unauthorized",
        content: "Blocked",
        category: "GENERAL",
        pinned: false,
        createdAt: now,
        updatedAt: now,
      })
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("chat_messages").get()
  );
  await assertFails(
    unauthDb.collection("users").doc(ALICE_UID).collection("terminal_logs").get()
  );
});

test("Authenticated owner: valid LearningNote create, list, partial update, and delete succeed", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 10000);
  const noteRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("notes")
    .doc("note_1");

  await assertSucceeds(
    noteRef.set({
      userId: ALICE_UID,
      title: "OWASP A01 Broken Access Control",
      content: "Always enforce server-side ownership checks on every endpoint.",
      category: "OWASP",
      pinned: true,
      createdAt: created,
      updatedAt: created,
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .orderBy("updatedAt", "desc")
      .get()
  );

  await assertSucceeds(
    noteRef.update({
      title: "OWASP A01 - Updated",
      pinned: false,
      updatedAt: new Date(Date.now() - 1000),
    })
  );

  await assertFails(
    noteRef.update({
      createdAt: new Date(Date.now() - 500),
      updatedAt: new Date(Date.now() - 500),
    })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .doc("note_2")
      .set({
        id: "note_2",
        userId: ALICE_UID,
        title: "Ghost",
        content: "Invalid",
        category: "GENERAL",
        pinned: false,
        createdAt: created,
        updatedAt: created,
      })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .doc("note_3")
      .set({
        userId: ALICE_UID,
        title: "Bad Category",
        content: "Content",
        category: "HACKING",
        pinned: false,
        createdAt: created,
        updatedAt: created,
      })
  );

  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("notes")
      .doc("note_4")
      .set({
        userId: ALICE_UID,
        title: "Future",
        content: "Content",
        category: "GENERAL",
        pinned: false,
        createdAt: new Date(Date.now() + 60000),
        updatedAt: created,
      })
  );

  await assertSucceeds(noteRef.delete());
});

test("Authenticated owner: valid ChatMessage create across new agentTypes, list, and delete succeed; update rejected", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const msgRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("chat_messages")
    .doc("msg_1");

  await assertSucceeds(
    msgRef.set({
      userId: ALICE_UID,
      role: "FRIDAY",
      content: "Namaste! Main FRIDAY hoon. Python code aur Web Research ready hai.",
      languageMode: "HINGLISH",
      agentType: "CODING",
      createdAt: created,
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("chat_messages")
      .doc("msg_2")
      .set({
        userId: ALICE_UID,
        role: "FRIDAY",
        content: "Web Research Summary in Hindi.",
        languageMode: "HINDI",
        agentType: "WEB_RESEARCH",
        createdAt: created,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("chat_messages")
      .orderBy("createdAt", "asc")
      .get()
  );

  await assertFails(msgRef.update({ content: "Tampered" }));
  await assertSucceeds(msgRef.delete());
});

test("Authenticated owner: valid TerminalLog create, list, and delete succeed; update and spoofed uid fail", async () => {
  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  const created = new Date(Date.now() - 5000);
  const logRef = aliceDb
    .collection("users")
    .doc(ALICE_UID)
    .collection("terminal_logs")
    .doc("log_1");

  await assertSucceeds(
    logRef.set({
      userId: ALICE_UID,
      command: "nmap -sn 192.168.1.0/24",
      environment: "LINUX_SERVER_BRIDGE",
      status: "EXECUTED",
      output: "Host 192.168.1.1 is up (0.004s latency).",
      exitCode: 0,
      approvedByUser: true,
      createdAt: created,
    })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("terminal_logs")
      .doc("log_timeout")
      .set({
        userId: ALICE_UID,
        command: "ping -c 10 127.0.0.1",
        environment: "TERMUX_BRIDGE",
        status: "TIMEOUT",
        output: "Timed out after 15s",
        exitCode: -1,
        approvedByUser: true,
        createdAt: created,
      })
  );

  await assertSucceeds(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("terminal_logs")
      .orderBy("createdAt", "desc")
      .get()
  );

  // Immutable audit log
  await assertFails(logRef.update({ status: "CANCELLED" }));

  // Reject spoofed UID
  await assertFails(
    aliceDb
      .collection("users")
      .doc(ALICE_UID)
      .collection("terminal_logs")
      .doc("log_spoof")
      .set({
        userId: BOB_UID,
        command: "whoami",
        environment: "TERMUX_BRIDGE",
        status: "EXECUTED",
        output: "u0_a123",
        exitCode: 0,
        approvedByUser: true,
        createdAt: created,
      })
  );

  await assertSucceeds(logRef.delete());
});

test("Cross-user isolation: Alice cannot read or write Bob's notes, chat_messages, or terminal_logs", async () => {
  const created = new Date(Date.now() - 10000);
  await testEnv.withSecurityRulesDisabled(async (context) => {
    const db = context.firestore();
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("notes")
      .doc("bob_note")
      .set({
        userId: BOB_UID,
        title: "Bob Secret Note",
        content: "Top secret",
        category: "LINUX",
        pinned: false,
        createdAt: created,
        updatedAt: created,
      });
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("chat_messages")
      .doc("bob_msg")
      .set({
        userId: BOB_UID,
        role: "USER",
        content: "Explain nmap",
        languageMode: "ENGLISH",
        agentType: "CHAT",
        createdAt: created,
      });
    await db
      .collection("users")
      .doc(BOB_UID)
      .collection("terminal_logs")
      .doc("bob_log")
      .set({
        userId: BOB_UID,
        command: "uname -a",
        environment: "TERMUX_BRIDGE",
        status: "EXECUTED",
        output: "Linux localhost",
        exitCode: 0,
        approvedByUser: true,
        createdAt: created,
      });
  });

  const aliceDb = testEnv.authenticatedContext(ALICE_UID).firestore();
  await assertFails(
    aliceDb.collection("users").doc(BOB_UID).collection("notes").get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("notes")
      .doc("bob_note")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("notes")
      .doc("bob_note")
      .update({ title: "Hijacked", updatedAt: new Date(Date.now() - 1000) })
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("notes")
      .doc("bob_note")
      .delete()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("chat_messages")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("terminal_logs")
      .get()
  );
  await assertFails(
    aliceDb
      .collection("users")
      .doc(BOB_UID)
      .collection("terminal_logs")
      .doc("bob_log")
      .delete()
  );
});
