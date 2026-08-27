package com.apimarketplace.common.folder;

/**
 * A folder operation the caller asked for and that cannot be carried out. The
 * {@link Code} is what the REST layer maps to a status - the message is for logs and for
 * the error body, never for branching.
 */
public class ResourceFolderException extends RuntimeException {

    public enum Code {
        /** No such folder in the caller's workspace (also covers "exists elsewhere"). */
        NOT_FOUND,
        /** The requested parent folder does not exist in the caller's workspace. */
        PARENT_NOT_FOUND,
        /** Empty name, or longer than {@link AbstractResourceFolderEntity#MAX_NAME_LENGTH}. */
        INVALID_NAME,
        /** The move would put a folder inside itself or inside one of its own descendants. */
        CYCLE
    }

    private final Code code;

    public ResourceFolderException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code getCode() {
        return code;
    }
}
