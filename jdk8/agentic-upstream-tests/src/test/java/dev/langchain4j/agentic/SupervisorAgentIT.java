package dev.langchain4j.agentic;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import dev.langchain4j.service.V;

class SupervisorAgentIT {

    static class Document {

        private final String author;

        Document(String author) {
            this.author = author;
        }

        public String getAuthor() {
            return author;
        }
    }

    static class Invoice extends Document {

        private final double amountInUSD;

        // The planner returns the argument as a JSON object, which AgentUtil.adaptValueToType reads
        // back into this type. A class with final fields and no no-arg constructor gives Jackson
        // nothing to construct from, so the creator has to be named explicitly. The other argument
        // fixtures here are records, which carry that for free; this one cannot be, because the
        // point of the test is a field inherited from a superclass.
        @JsonCreator
        Invoice(@JsonProperty("author") String author, @JsonProperty("amountInUSD") double amountInUSD) {
            super(author);
            this.amountInUSD = amountInUSD;
        }

        public double getAmountInUSD() {
            return amountInUSD;
        }

        // The agent renders this straight into its prompt with {{invoice}}, and a template variable
        // becomes text through toString(). Without one the model is asked to register
        // "Invoice@4f2a1b3c" and the supervisor keeps retrying. Records get this for free, which is
        // why the other argument fixtures here do not need it.
        @Override
        public String toString() {
            return "invoice of " + amountInUSD + " USD authored by " + getAuthor();
        }
    }

    public interface InvoiceRegistrationAgent {

        // Asking a model with no tools to "register" something gets a description of how one would
        // register an invoice, never a confirmation that one was registered. The supervisor reads
        // that as the request still being open and calls this agent again until it hits
        // maxAgentsInvocations, which surfaces as a wrong invocation count. Giving it a role it can
        // play and asking for the confirmation explicitly is what the banker agents above do with
        // "and return the new balance".
        @SystemMessage("""
            You are an invoice registry: you record every invoice you are given.
            """)
        @UserMessage("""
            Record the invoice described as '{{invoice}}', then confirm in a single sentence
            that it has been registered, repeating its author and its amount.
            """)
        @Agent("An agent that registers invoices")
        String register(@V("invoice") Invoice invoice);
    }
}
