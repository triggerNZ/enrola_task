package com.enrola.agent;

/**
 * The standing instructions that go with a call to the model: {@code system} is who the agent is
 * and what it may say, {@code outreach} is the one-off instruction for writing the first message.
 *
 * <p>Passed in per call rather than held, because two conversations running at once may be on
 * different versions -- a conversation keeps the instructions it opened with, whatever has been
 * edited since. A plain value with no Spring annotations: whoever resolves the version builds it.
 */
public record Prompts(String system, String outreach) {}
