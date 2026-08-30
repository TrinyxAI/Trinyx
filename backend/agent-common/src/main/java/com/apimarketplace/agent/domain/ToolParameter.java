package com.apimarketplace.agent.domain;

import lombok.Builder;

import java.util.List;
import java.util.Map;

/**
 * Parameter definition for a tool.
 */
@Builder
public record ToolParameter(
    /**
     * Parameter name
     */
    String name,

    /**
     * Parameter type (string, number, boolean, array, object)
     */
    String type,

    /**
     * Description of the parameter
     */
    String description,

    /**
     * Whether the parameter is required
     */
    boolean required,

    /**
     * Default value if not provided
     */
    Object defaultValue,

    /**
     * Enum values if the parameter is an enum
     */
    List<String> enumValues,

    /**
     * Nested properties for object types
     */
    Map<String, ToolParameter> properties,
    
    // ===== VALIDATION CONSTRAINTS =====
    
    /**
     * Minimum length for string parameters
     */
    Integer minLength,
    
    /**
     * Maximum length for string parameters
     */
    Integer maxLength,
    
    /**
     * Minimum value for number parameters
     */
    Double minimum,
    
    /**
     * Maximum value for number parameters
     */
    Double maximum,
    
    /**
     * Pattern (regex) for string validation
     */
    String pattern,

    /**
     * What one element of an array parameter is, as a JSON Schema type name.
     *
     * <p>Only read for {@code type = "array"}, and null means "string", which is what every array
     * parameter emitted before this field existed. It exists because the emitted schema is the
     * only description of a parameter a strict provider will honour: a parameter that carries
     * objects while its schema says its elements are strings can be refused or stringified before
     * the call is ever made, and the prose in the description cannot override it.
     */
    String itemType
) {}
