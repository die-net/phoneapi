package android.content;

// A copy of the framework's hidden interface, so the helper can implement it. At runtime the
// framework's own class is loaded first, and this one is only the compile-time view of it.
oneway interface IOnPrimaryClipChangedListener {
    void dispatchPrimaryClipChanged();
}
