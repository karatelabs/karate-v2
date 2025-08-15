package io.karatelabs.gherkin;

import io.karatelabs.common.Source;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class Feature {

    public final Source source;

    private int line;
    private List<Tag> tags;
    private String name;
    private String description;

    public Feature(Source source) {
        this.source = source;
    }

    public int getLine() {
        return line;
    }

    public void setLine(int line) {
        this.line = line;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    public List<Tag> getTags() {
        return tags == null ? Collections.emptyList() : tags;
    }

    public void setTags(List<Tag> tags) {
        this.tags = tags;
    }

    public void addTag(Tag tag) {
        if (tags == null) {
            tags = new ArrayList<>();
        }
        tags.add(tag);
    }

    @Override
    public String toString() {
        return source.getPathForLog();
    }

}
