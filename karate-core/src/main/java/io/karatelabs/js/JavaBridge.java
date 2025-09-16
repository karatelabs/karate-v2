package io.karatelabs.js;

public interface JavaBridge {

    default JavaAccess forClass(String className) {
        try {
            return new JavaClass(className);
        } catch (Exception e) {
            return null;
        }
    }

    default JavaAccess forObject(Object object) {
        if (object == null) {
            throw new NullPointerException("object is null");
        }
        return new JavaObject(object);
    }

}
