# The index snapshot uses Java serialization, which matches classes by their
# fully-qualified name on read. Keep our names so a snapshot stays readable across
# builds; shrinking and optimization (the parts that matter for speed) stay on.
-keepnames class com.igviewer.archive.**
