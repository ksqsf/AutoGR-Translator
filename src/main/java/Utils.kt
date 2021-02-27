fun quote(str: String): String {
    return str.replace("\\", "\\\\")
        .replace("\n", "\\n")
        .replace("\"", "\\\"")
}
