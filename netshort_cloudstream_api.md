# NetShort Abroad — Streaming API Analysis
**Target**: `com.netshort.abroad` v2.1.3  
**Purpose**: CloudStream provider implementation  
**Method**: JADX decompilation of 38,624 classes across 8 DEX files

---

## 🏗️ API Infrastructure

| Property | Value |
|----------|-------|
| **Production Base URL** | `https://appsecapi.netshort.com/prod-app-api` |
| **Dev Base URL** | `http://app.dev.netshort.net/dev-app-api` |
| **Body Type** | JSON (via `hjq.http` library) |
| **HTTP Client** | OkHttp (15s connect, 20s read, 15s write) |
| **Video CDN** | ByteDance/VolcEngine VOD (`sdkVid` + `playVoucher`) |

> [!IMPORTANT]
> All API calls are POST with JSON body to `https://appsecapi.netshort.com/prod-app-api{path}`

---

## 🎬 Core Content APIs (for CloudStream)

### 1. Home Page / Recommendations
```
POST /video/shortPlay/recommend/load_user_recommend
Body: { offset, limit, policyType, reload, codec, playClarity }
Response fields:
  data.dataList[]:
    shortPlayId, shortPlayName, shortPlayCover, shotIntroduce
    totalEpisode, isFinish, language, episodeId, episodeNo
    classificationList[], actorList[], shortPlayLabels, sdkVid
```

### 2. Drama Detail (Series Info + Episode List)
```
POST /video/shortPlay/base/detail_info/V2
Body: { shortPlayId, codec, playClarity, isRequestReserve: false }
Response fields:
  shortPlayId, shortPlayName, shortPlayCover, shotIntroduce
  totalEpisode, isFinish, publishTime, language, payPoint
  shortPlayEpisodeInfos[]:
    episodeId, episodeNo, episodeCover, videoName
    isLock, isVip, isAd, episodeType, episodeGoldCoinPrice
    playVoucher, sdkVid, playToken, playClarity
    subtitleList[]
  actorList[], classificationList[], shortPlayLabelList[]
```

### 3. Episode Stream URL
```
POST /video/shortPlay/base/episode/detail_info
Body: { shortPlayId, episodeId, episodeType, episodeNo, codec, playClarity }
Response fields: (same as VideoEpisodeInfosBean)
  episodeId, episodeNo, sdkVid, playVoucher, playToken
  episodeClarityList[]: { playClarity, playVoucher, playType, expireTime }
```

> [!WARNING]
> Videos are **NOT** served as plain m3u8 URLs. They use ByteDance/VolcEngine VOD SDK:
> - `sdkVid` = VolcEngine video ID  
> - `playVoucher` = time-limited access token  
> - The SDK calls `vod.ap-southeast-1.volcengineapi.com` or `vod.bytedanceapi.com` to get the actual m3u8

### 4. Search
```
POST /video/shortPlay/search/searchByKeyword
Body: { keyword, pageNo, pageSize, ... }

POST /video/shortPlay/tab/search/recommend
(Search suggestions / popular)

POST /video/shortPlay/recommend/search_shortPlay_recommend  
(Trending searches)

POST /video/shortPlay/recommend/load_shaded_word
(Search hint/autocomplete)
```

### 5. Categories / Browse
```
POST /video/classes/constant
(Get all category type constants)

POST /video/classes/page
Body: { pageNo, pageSize, classType, ... }
(Paginated list by category)

POST /video/shortPlay/tab/load_all_chart
(Ranking / top charts)

POST /video/shortPlay/tab/load_page_free_group
(Free drama listing)

POST /video/recommend/list
(Smart recommendation list)
```

### 6. Similar / Related
```
POST /video/shortPlay/recommend/similar_picks
Body: { shortPlayId, ... }

POST /video/shortPlay/recommend/getShortPlayInfoByLabel
Body: { labelId, ... }
```

---

## 🔐 Auth APIs

```
POST /auth/login           — Login (Google/Facebook/Email)
POST /auth/bind            — Bind account
POST /auth/logout          — Logout
POST /auth/refresh_token   — Refresh auth token
POST /auth/sms_email/code  — Email OTP
POST /auth/update_language — Update user language
POST /auth/whetherBelong   — Check account ownership
```

---

## 👤 User / History APIs

```
POST /user/shortPlay/userBase/play_history
POST /user/shortPlay/userBase/last_play_history
POST /user/shortPlay/userBase/sync_play_history
POST /user/shortPlay/userBase/first_play
POST /user/shortPlay/userBase/unlock_shortPlay_episodeV2  — Coin unlock
POST /user/shortPlay/userBase/unlock_free_ad_episode      — Ad unlock
POST /user/shortPlay/userBase/clean_play_history

POST /user/shortPlay/appChaseShortPlay/add      — Follow/chase drama
POST /user/shortPlay/appChaseShortPlay/cancel
POST /user/shortPlay/appChaseShortPlay/list     — Following list
POST /user/shortPlay/appChaseShortPlay/batchCancel

POST /user/shortPlay/reserve/list
POST /user/shortPlay/reserve/load_today_online
```

---

## 📺 Video Player APIs

```
POST /video/video/load_video_definition_config
(Available quality options: SD/HD/FHD)

POST /video/video/ad_app_open
(Splash ad config)

POST /video/video/load_carousel_chart
(Discover banner carousel)

POST /video/shortPlay/recommend/load_user_recommend
POST /video/shortPlay/recommend/loadBackRecommend
POST /video/shortPlay/recommend/load_completed_pop_up
POST /video/shortPlay/recommend/get_latest_short_play_subtitle_info
POST /video/shortPlay/recommend/load_natural_new_users_shortPlay
```

---

## 🌐 App Config APIs

```
POST /base/global/home_page
POST /base/global/languages
POST /base/global/landPage
POST /base/global/global_config
POST /base/global/login_switchV2
POST /base/appConfig/newestVersion   (app update check)
```

---

## 🎭 VolcEngine VOD — Stream Resolution

The video stream is NOT a direct m3u8 URL in the API response. The flow is:

```
1. API returns: { sdkVid: "xxxx", playVoucher: "token..." }
2. App calls VolcEngine VOD SDK → getPlayInfo(sdkVid, playVoucher)
3. SDK contacts: vod.ap-southeast-1.volcengineapi.com
4. Returns: actual CDN m3u8 URL
```

**For CloudStream provider**, you need to either:
- **Option A**: Call the VolcEngine VOD API directly:
  ```
  GET https://vod.ap-southeast-1.volcengineapi.com/v1/fetchAuditVideoInfo
  with vid= and X-Timestamp, X-Signature params
  ```
- **Option B**: Use `playVoucher` with the public VolcEngine API  
  (requires reverse engineering the SDK signing, complex)
- **Option C**: Intercept network traffic at runtime with HTTP proxy to capture the m3u8

---

## 📋 JSON Request Template (CloudStream)

```kotlin
// All requests use this pattern:
val response = app.post(
    "$baseUrl$endpoint",
    headers = mapOf(
        "Content-Type" to "application/json",
        "Authorization" to "Bearer $token",   // from /auth/login
        "language" to "en",
        "User-Agent" to "okhttp/3.xx.xx"
    ),
    data = mapOf(...)  // JSON body fields
)
```

---

## 📦 Data Model Summary (for Kotlin data classes)

```kotlin
// Search / Home item:
data class DramaItem(
    val shortPlayId: String,
    val shortPlayName: String,
    val shortPlayCover: String,
    val shotIntroduce: String?,
    val totalEpisode: Int,
    val isFinish: Int,       // 0=ongoing, 1=complete
    val language: String,
    val classificationList: List<Classification>?,
    val actorList: List<Actor>?,
    val shortPlayLabels: String?
)

// Episode:
data class EpisodeItem(
    val episodeId: String,
    val episodeNo: Int,
    val videoName: String?,
    val episodeCover: String?,
    val isLock: Boolean,
    val isVip: Boolean,
    val sdkVid: String,      // VolcEngine video ID
    val playVoucher: String  // VolcEngine access token
)
```

---

> [!NOTE]
> **Subscription model**: Episodes 1–N are free, further episodes require coins or VIP membership.
> `isLock=true` = paid episode. `isVip=true` = VIP-only.
> For CloudStream, only free episodes (`isLock=false`) will be playable without auth.
