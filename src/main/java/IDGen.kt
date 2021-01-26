object IDGen {
    var idCnt = 0
    fun gen(): Int {
        idCnt += 1
        return idCnt
    }
}