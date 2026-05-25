// use an integer for version numbers
version = 1

cloudstream {
    description = "FTPBD provider"
    authors = listOf("Nehal")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     */
    status = 1

    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )
    language = "bn"

    iconUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAABwAAAAcCAMAAABF0y+mAAAAt1BMVEWFeW19tkJ9tkJjd3VHcEyGeGXkXUHUHF/UHF/UHF99tkJ9tkJ9tkLVGV/UHF99tkLUHF/UG199tkJ9tkLUHF/rdjV3hGVxc3Zxc3Zxc3Zxc3ZudHZicX9xc3ZwcXNxc3Zxc3Zxc3Zxc3Zrc3xxc3Zyc3Vxc3YleLYleLYgb7EZb7DzlSPzlSMdc7MRZ6vzlSMhdbQQZqrzlSMleLbzlSMjeLcAUpYleLYkeLbzlSPzlSPzlSPzlSNAuOE6AAAAPXRSTlMJwv8SACAQx//Ate5UUd51cqHRnPImRIydaW98OmHs/7DXzi72VcH/V3gbUv9s/m+f56HFxE5w9unxbd516BumlQAAAbRJREFUeAFciwUSwzAQxC6XTWuGMP7/mzWUZbZGRA03LQptB3S3er/dhSRSzKzTyzTpAgghpE1Ply/EGZi0KW8AG6IQwkJkqM/lwDqJF3YUMZeOuuSYB/yQ6lGIiQAa2OOPIGIyac7/XW1tkVqjXdZtW/djWff1XNZr2TGOWRp+1E0WOA7EUAyNNl5sKyyThzFlhvufa51l9GDy5E9SVMuUXQQhSBtYEhFHLVkNntQdSHTj21sS45gJyBEeHgVvngBt9CfAhCEwYJgy8FlhjErVRjaOgR77QJ+9t4LvzK1Svm9ElD1k+joK36cFbqcvMKagXq8Qd/DwHwnGeQGprPSyxtqXN6TadYzNZ57N56Ixe1mIgDFTYOHuDPKl4Gy+EkwZR0SYYDoF1g4Gmxww4/kKFj2mHIMZmABuK7jLd2lglnsQsjGy7N2yr5S1IA7HLqeSSDjmYMJBFgGnEzzcKat90y1wa2+NMsrooVmq4B86u7Un1SE/5PJ+lXzu5NTnJs/z4zI/7j5RfXLri3PuYnIv5ZW7LASucimf83p1Aij8D/zmtgbgnVsjunk/DhXQeT8OW7FnfeMzkPwOWpQAAAAASUVORK5CYII="
}
