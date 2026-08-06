/**
 * What the model can do besides write a reply: look up a price, check whether a tier covers a
 * treatment, hand the conversation to a consultant.
 *
 * <p>Each implements {@link com.enrola.agent.tools.AgentTool} and is picked up as a bean, so
 * {@link com.enrola.agent.ChatAgent} offers whichever ones exist and dispatches by name. Adding
 * an ability means adding a class here and nothing else.
 *
 * <p>A tool needing the rest of the application reaches it through an interface declared in the
 * parent package -- {@link com.enrola.agent.CallbackTool} is the one so far -- so this package
 * stays as free of the application as {@code agent} itself.
 */
package com.enrola.agent.tools;
