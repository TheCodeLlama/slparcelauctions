import { describe, it, expect, beforeEach } from "vitest";
import {
  awaitAuthReady,
  beginAuthBootstrap,
  getAccessToken,
  markAuthReady,
  setAccessToken,
} from "./session";

describe("session token ref", () => {
  beforeEach(() => {
    setAccessToken(null);
    // Reset the gate to resolved between tests so this suite's later cases
    // don't poison subsequent tests (the module-level promise persists across
    // test cases).
    markAuthReady();
  });

  it("getAccessToken returns null by default", () => {
    expect(getAccessToken()).toBeNull();
  });

  it("setAccessToken stores the value", () => {
    setAccessToken("token-abc");
    expect(getAccessToken()).toBe("token-abc");
  });

  it("setAccessToken(null) clears the stored value", () => {
    setAccessToken("token-abc");
    setAccessToken(null);
    expect(getAccessToken()).toBeNull();
  });
});

describe("auth-ready gate", () => {
  beforeEach(() => {
    // Default-resolved state for each test.
    markAuthReady();
  });

  it("awaitAuthReady() resolves immediately by default (tests + SSR don't hang)", async () => {
    await expect(awaitAuthReady()).resolves.toBeUndefined();
  });

  it("beginAuthBootstrap() flips the gate to pending until markAuthReady()", async () => {
    beginAuthBootstrap();

    // The gate is pending — racing a fast timeout proves the promise has not
    // resolved synchronously.
    const sentinel = Symbol("not-resolved");
    const raced = await Promise.race([
      awaitAuthReady().then(() => "resolved"),
      new Promise((r) => setTimeout(() => r(sentinel), 25)),
    ]);
    expect(raced).toBe(sentinel);

    // Closing the gate releases any waiter.
    markAuthReady();
    await expect(awaitAuthReady()).resolves.toBeUndefined();
  });

  it("the gate's resolved value is the token that was set before markAuthReady fired", async () => {
    beginAuthBootstrap();

    // Simulate the production flow: a fetch starts before the bootstrap
    // resolves and awaits the gate. The bootstrap sets the token *before*
    // closing the gate; the waiter should observe the post-bootstrap value.
    const waiter = (async () => {
      await awaitAuthReady();
      return getAccessToken();
    })();

    setAccessToken("token-from-bootstrap");
    markAuthReady();

    await expect(waiter).resolves.toBe("token-from-bootstrap");
  });
});
