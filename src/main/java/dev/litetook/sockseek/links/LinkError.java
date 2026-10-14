package dev.litetook.sockseek.links;

/** A link that cannot be read, with a message meant for the person who passed it. */
public class LinkError extends RuntimeException {

    public LinkError(String message) {
        super(message);
    }
}
