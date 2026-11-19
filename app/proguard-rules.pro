# Remove as checagens de nulidade geradas pelo Kotlin (código menor e mais rápido).
-assumenosideeffects class kotlin.jvm.internal.Intrinsics {
    public static void checkNotNull(...);
    public static void checkNotNullParameter(...);
    public static void checkNotNullExpressionValue(...);
    public static void checkExpressionValueIsNotNull(...);
    public static void checkParameterIsNotNull(...);
    public static void checkReturnedValueIsNotNull(...);
    public static void checkFieldIsNotNull(...);
}

# ML Kit (digitalizador): os registradores de componentes são criados por reflexão a partir do
# manifesto. Sem isto o R8 tira o construtor e o getComponents, e o scanner cai com
# NullPointerException ao abrir (SharedPrefManager ausente).
-keep class * implements com.google.firebase.components.ComponentRegistrar {
    <init>();
    public java.util.List getComponents();
}
