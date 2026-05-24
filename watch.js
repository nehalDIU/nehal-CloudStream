let animeId = null;
let epId = null;
let epNumber = null;
let isLoggedIn = user.is_logged_in || false;
window.addEventListener('hianime:user-ready', function (event) {
    isLoggedIn = !!(event.detail && event.detail.is_logged_in);
});
let watchingLogInterval = null;
let watchingLogTimeout = null;
let lastContinueWatchingKey = '';
let continueWatchingRequestInFlight = false;
let currentVideoData = {
    currentTime: 0,
    duration: 0
};
const hiEpisodeCacheTtl = 6 * 60 * 60 * 1000;
const hiContinueWatchingInitialDelay = 60 * 1000;
const hiContinueWatchingInterval = 60 * 1000;
const hiPendingRequests = {};
let hiPageFullyLoaded = document.readyState === 'complete';

window.addEventListener('load', function () {
    hiPageFullyLoaded = true;
});

function hiRestUrl(path) {
    const baseUrl = (hianime_ep_ajax.rest_url || '').replace(/\/+$/, '');
    return baseUrl + '/' + String(path || '').replace(/^\/+/, '');
}

function hiCacheGet(key) {
    try {
        const cached = sessionStorage.getItem(key);
        if (!cached) return null;

        const item = JSON.parse(cached);
        if (!item || !item.expires || item.expires < Date.now()) {
            sessionStorage.removeItem(key);
            return null;
        }

        return item.value;
    } catch (e) {
        return null;
    }
}

function hiCacheSet(key, value) {
    try {
        sessionStorage.setItem(key, JSON.stringify({
            expires: Date.now() + hiEpisodeCacheTtl,
            value: value
        }));
    } catch (e) {}
}

function hiFetchPublicJson(url, options = {}) {
    const timeout = options.timeout || 3000;
    const retries = options.retries || 3;
    const credentials = options.credentials || 'omit';

    function attempt(remaining) {
        if (window.fetch) {
            const controller = window.AbortController ? new AbortController() : null;
            const timer = controller ? setTimeout(() => controller.abort(), timeout) : null;

            return fetch(url, {
                method: 'GET',
                credentials: credentials,
                cache: 'no-store',
                signal: controller ? controller.signal : undefined,
                headers: {
                    'Accept': 'application/json',
                    'Cache-Control': 'no-cache'
                }
            }).then((response) => {
                if (timer) clearTimeout(timer);
                if (!response.ok) {
                    throw new Error(response.statusText || 'Request failed');
                }

                return response.json();
            }).catch((error) => {
                if (timer) clearTimeout(timer);
                if (remaining > 1) {
                    return attempt(remaining - 1);
                }
                throw error;
            });
        }

        return $.ajax({
            url: url,
            type: 'GET',
            timeout: timeout
        }).catch((error) => {
            if (remaining > 1) {
                return attempt(remaining - 1);
            }
            throw error;
        });
    }

    return attempt(retries);
}

function hiAfterPageLoad(callback) {
    if (hiPageFullyLoaded || document.readyState === 'complete') {
        setTimeout(callback, 0);
        return;
    }

    window.addEventListener('load', function () {
        setTimeout(callback, 0);
    }, { once: true });
}

function selectEpisode($episode, options = {}) {
    if (!$episode || !$episode.length) {
        return;
    }

    const episodeId = $episode.data('id');
    if (!episodeId) {
        console.error('Episode ID not found');
        return;
    }

    const isSameEpisode = String(episodeId) === String(epId || '');

    $('.ep-item').removeClass('active');
    $episode.addClass('active');
    epId = episodeId;
    epNumber = $episode.data('number');

    if (!isSameEpisode || options.reloadSources) {
        loadSources(episodeId);
    } else if (!$('.player-frame iframe').length) {
        selectServer($('#servers-content .server-item').first());
    }

    if (!options.skipHistory) {
        const episodeUrl = $episode.attr('href');
        if (episodeUrl && episodeUrl !== window.location.href && episodeUrl !== window.location.pathname) {
            history.pushState(null, '', episodeUrl);
        }
    }

    $('#wp-admin-bar-edit a').attr("href", "/wp-admin/post.php?post=" + epId + "&action=edit");
}

function selectServer($serverItem) {
    if (!$serverItem || !$serverItem.length) {
        return;
    }

    const serverName = $serverItem.data('server-name');
    const serverHash = $serverItem.data('hash');
    if (!serverName || !serverHash) {
        console.error('Server name or hash not found');
        return;
    }

    $('#servers-content .server-item .btn').removeClass('active');
    $serverItem.find('.btn').addClass('active');

    const embedUrl = atob(serverHash);
    const iframe = document.createElement('iframe');
    iframe.title = serverName;
    iframe.frameBorder = '0';
    iframe.allowFullscreen = true;
    iframe.src = embedUrl;

    $('.player-frame').empty().append(iframe);
}

function handleEpisodeListResponse(response) {
    if (!response?.status) {
        console.error('Failed to load episode list');
        $('#episodes-content').html('<div class="alert alert-warning">Failed to load episodes. Please refresh the page.</div>');
        return;
    }

    if (response.totalItems > 1) {
        $('.block-prev, .block-next').show();
    }

    $('#episodes-content').html(response.html);

    if ($('.toggle-lang').hasClass('off')) {
        toggleAnimeName();
    }

    const $selectedEpisode = $('.ep-item[data-id="' + epId + '"]').first();
    if ($selectedEpisode.length) {
        selectEpisode($selectedEpisode, { skipHistory: true });
    }

    if ($(".ep-page-item").length > 0) {
        const activePage = $(".ep-item.active").parent().data("page");
        $(".ep-page-item[data-page=" + activePage + "]").click();
    } else {
        const activePage = $(".ep-item.active").parent().data("page");
        $("#episodes-page-" + activePage).show();
    }
}

function handleSourcesResponse(response) {
    if (!response?.status || !response?.html) {
        console.error('Failed to load sources');
        $('#servers-content').html('<div class="alert alert-warning">Failed to load sources. Please refresh the page.</div>');
        return;
    }

    $('#servers-content').html(response.html);
    $('#servers-content .loading-relative').remove();

    const firstServerItem = $('#servers-content .server-item').first();
    if (firstServerItem.length) {
        selectServer(firstServerItem);
        currentVideoData = {
            currentTime: 0,
            duration: 0
        };
        startLoggingContinueWatching();
    }
}

function hydrateInitialWatchPage() {
    if (!$('#episodes-content .ep-item').length) {
        loadEpisodeList();
        return;
    }

    if ($('.ep-item').length > 1) {
        $('.block-prev, .block-next').show();
    }

    let $selectedEpisode = $('.ep-item[data-id="' + epId + '"]').first();
    if (!$selectedEpisode.length) {
        $selectedEpisode = $('.ep-item.active').first();
    }
    if (!$selectedEpisode.length) {
        $selectedEpisode = $('.ep-item').first();
    }

    if ($selectedEpisode.length) {
        $('.ep-item').removeClass('active');
        $selectedEpisode.addClass('active');
        epId = $selectedEpisode.data('id');
        epNumber = $selectedEpisode.data('number');
        $('#wp-admin-bar-edit a').attr("href", "/wp-admin/post.php?post=" + epId + "&action=edit");
    }

    if ($(".ep-page-item").length > 0) {
        const activePage = $(".ep-item.active").parent().data("page");
        $(".ep-page-item").removeClass("active");
        $(".ep-page-item .ic-active").hide();
        const $pageItem = $(".ep-page-item[data-page=" + activePage + "]").first();
        $pageItem.addClass("active");
        $pageItem.find(".ic-active").show();
        $(".ss-list-min").hide().removeClass("active");
        $("#episodes-page-" + activePage).show().addClass("active");
        $("#current-page").text($pageItem.text().trim());
    } else {
        $(".ss-list").show().addClass("active");
    }

    if ($('.toggle-lang').hasClass('off')) {
        toggleAnimeName();
    }

    const initialServersHtml = $('#servers-content').html();
    if (initialServersHtml && epId) {
        hiCacheSet('hi_episode_servers_' + epId, {
            status: true,
            html: initialServersHtml
        });
    }

    hiAfterPageLoad(function () {
        const firstServerItem = $('#servers-content .server-item').first();
        if (firstServerItem.length) {
            selectServer(firstServerItem);
            currentVideoData = {
                currentTime: 0,
                duration: 0
            };
            startLoggingContinueWatching();
        } else {
            $('.player-frame').empty();
        }
    });
}
// Main initialization
$(document).ready(() => {
    initializeAnimeId();

    $(document).on('click', '.ep-item', function (e) {
        e.preventDefault();
        selectEpisode($(this));
    });

    $(document).on('click', '#servers-content .server-item', function (e) {
        e.preventDefault();
        selectServer($(this));
    });

    $(document).on("click", ".ep-page-item", function () {
        $(".ep-page-item").removeClass("active"),
            $(".ep-page-item .ic-active").hide(),
            $(this).addClass("active"),
            $(this).find(".ic-active").show(),
            $(".ss-list-min").hide(),
            $(".ss-list-min").removeClass("active"),
            $("#episodes-page-" + $(this).data("page")).show(),
            $("#episodes-page-" + $(this).data("page")).addClass("active"),
            $("#current-page").text($(this).text().trim())
    });
    // Search episode functionality
    $(document).on("keyup", "#search-ep", function (e) {
        e.preventDefault();

        var targetElement, inputValue = e.target.value;

        // Remove highlight from all episode items
        $(".ep-item").removeClass("highlight");

        if (inputValue) {
            // Find episode item with matching data-number
            var episodeItem = $(".ep-item[data-number=" + inputValue + "]");

            if (episodeItem.length > 0) {
                // Get the parent container
                targetElement = episodeItem.parent();

                // Click on the corresponding page item
                $(".ep-page-item[data-page=" + targetElement.data("page") + "]").click();

                // If Enter key was pressed (keyCode 13)
                if (e.keyCode === 13) {
                    // Clear the input and click the episode item
                    $(e.target).val("");
                    episodeItem.click();
                } else {
                    // Just highlight the episode item
                    episodeItem.addClass("highlight");
                }
            }
        } else {
            // If input is empty, go to the page containing the active episode
            targetElement = $(".ep-item.active").parent().data("page");
            $(".ep-page-item[data-page=" + targetElement + "]").click();
        }
    });

    $("#media-resize").click(function (e) {
        $(".anis-watch-wrap").toggleClass("extend");
        if ($(".anis-watch-wrap").hasClass("extend")) {
            $(this).html('<i class="fas fa-compress mr-1"></i>Collapse');
        } else {
            $(this).html('<i class="fas fa-expand mr-1"></i>Expand');
        }
    });
    $("#turn-off-light").click(function (e) {
        $("#mask-overlay, .anis-watch-wrap").toggleClass("active");
    });
    $("#mask-overlay").click(function (e) {
        $("#mask-overlay, .anis-watch-wrap").removeClass("active");
        $("#turn-off-light").removeClass("off");
    });
    $(".dt-comment").click(function () {
        window.scrollTo({
            top: document.getElementById("comment-block").offsetTop,
            behavior: 'smooth'
        });
    });

    // Auto play setting
    if (parseInt(getUserSetting('auto_play', '0')) === 1) {
        $('.quick-settings[data-option="auto_play"]').removeClass("off");
    }

    // Auto next setting
    if (parseInt(getUserSetting('auto_next', '0')) === 1) {
        $('.quick-settings[data-option="auto_next"]').removeClass("off");
    }

    // Auto skip intro setting
    if (parseInt(getUserSetting('auto_skip_intro', '0')) === 1) {
        $('.quick-settings[data-option="auto_skip_intro"]').removeClass("off");
    }
  
    // Quick settings click handler
    $(".quick-settings").click(function () {
        const option = $(this).data("option");
        const isOff = $(this).hasClass("off");
        updateLocalStorageSetting(option, isOff ? '0' : '1');
    });

    $(".btn-emo").click(function (){
        $(this).closest('.block-rating').addClass('rated');
        $(this).siblings().removeClass('emo-rated');
        $(this).addClass('emo-rated');
        let ratings = window.localStorage.getItem('emo-ratings') || '{}';
        window.localStorage.setItem('emo-ratings', JSON.stringify({...JSON.parse(ratings), [`${$(this).data('id')}`]: $(this).data('mark')}));
    })

      // If user has rated this anime, auto-select the rating button
    let animeId = $('#ani_detail').data('anime-id');
    const ratings = JSON.parse(window.localStorage.getItem('emo-ratings') || '{}');
    if (ratings && ratings[animeId]) {
        $(`.btn-emo[data-id="${animeId}"][data-mark="${ratings[animeId]}"]`).click();
    }

    // Quick settings toggle handler
    $(".quick-settings-toggle").click(function () {
        $(this).toggleClass("active");
        const option = $(this).data("option");
        const isActive = $(this).hasClass("active");
        updateUserSetting(option, isActive ? 1 : 0);
    });

    updateScheduleDate();

});

function updateScheduleDate() {
    const $scheduleAlert = $(".schedule-alert");
    
    if ($scheduleAlert.length === 0) {
        return;
    }
    
    const $scheduleDate = $("#schedule-date");
    const dateValue = $scheduleDate.data("value");
    
    if (!dateValue) {
        return;
    }
    
    const isoDateString = dateValue.split(" ").join("T") + "Z";
    const date = new Date(isoDateString);
    
    if (!isNaN(date.getTime())) {
        const localizedDate = date.toLocaleString("en-US");
        $scheduleDate.text(localizedDate);
    }
}

function initializeAnimeId() {

    if (animeId) return;

    // Try to get anime ID from detail link // id="ani_detail" data-anime-id
    const animeDetail = $('#ani_detail');
    if (animeDetail.length) {
        animeId = animeDetail.data('anime-id');
        epId = animeDetail.data('id');
    }



    // Fallback: data attribute
    if (!animeId) {
        return;
    }
    if (animeId) {
        hydrateInitialWatchPage();
    }
}

function loadEpisodeList() {
    if (!animeId) {
        console.error('Anime ID not found');
        return;
    }

    const cacheKey = 'hi_episode_list_' + animeId;
    const cachedResponse = hiCacheGet(cacheKey);
    if (cachedResponse) {
        handleEpisodeListResponse(cachedResponse);
        return;
    }

    if (hiPendingRequests[cacheKey]) {
        return;
    }

    $('#episodes-content').html(`
        <div class="loading-relative loading-box loading-box-episodes">
            <div class="loading">
                <div class="span1"></div>
                <div class="span2"></div>
                <div class="span3"></div>
            </div>
        </div>
    `);

    hiFetchPublicJson(hiRestUrl('episode/list/' + animeId), { timeout: 250000, retries: 3 })
        .then((response) => {
            if (response?.status) {
                hiCacheSet(cacheKey, response);
            }
            handleEpisodeListResponse(response);
            delete hiPendingRequests[cacheKey];
        }, (error) => {
            console.error('AJAX error loading episode list:', error);
            $('#episodes-content').html(`<div class="alert alert-danger">Error loading episodes: ${error.message || error}</div>`);
            delete hiPendingRequests[cacheKey];
        });

    hiPendingRequests[cacheKey] = true;


}

let activeSourcesController = null;

function loadSources(episodeId) {
    if (!episodeId) {
        console.error('Episode ID not found');
        return;
    }

    if (activeSourcesController) {
        activeSourcesController.abort();
        activeSourcesController = null;
    }

    const cacheKey = 'hi_episode_servers_' + episodeId;
    const cachedResponse = hiCacheGet(cacheKey);
    if (cachedResponse) {
        handleSourcesResponse(cachedResponse);
        return;
    }

    if (hiPendingRequests[cacheKey]) {
        return;
    }

    $('#servers-content, .player-frame').html(`
        <div class="loading-relative loading-box">
            <div class="loading">
                <div class="span1"></div>
                <div class="span2"></div>
                <div class="span3"></div>
            </div>
        </div>
    `);

    const controller = window.AbortController ? new AbortController() : null;
    activeSourcesController = controller;

    const url = hiRestUrl('episode/servers/' + episodeId);
    const fetchPromise = controller
        ? fetch(url, {
            method: 'GET',
            credentials: 'omit',
            cache: 'no-store',
            signal: controller.signal,
            headers: { 'Accept': 'application/json', 'Cache-Control': 'no-cache' }
          }).then(r => { if (!r.ok) throw new Error(r.statusText); return r.json(); })
        : hiFetchPublicJson(url, { timeout: 250000, retries: 3 });

    hiPendingRequests[cacheKey] = true;

    fetchPromise.then((response) => {
            if (response?.status && response?.html) {
                hiCacheSet(cacheKey, response);
            }
            handleSourcesResponse(response);
            delete hiPendingRequests[cacheKey];
            if (activeSourcesController === controller) activeSourcesController = null;
        }, (error) => {
            if (error?.name === 'AbortError') return;
            console.error('AJAX error loading sources:', error);
            $('#servers-content').html(`<div class="alert alert-danger">Error loading sources: ${error.message || error}</div>`);
            delete hiPendingRequests[cacheKey];
            if (activeSourcesController === controller) activeSourcesController = null;
        });
}


function toggleAnimeName() {
    $(".dynamic-name").each(function () {
        var currentName = $(this).text(),
            jName = $(this).data("jname"),
            _this = $(this);
        _this.animate(
            {
                opacity: 0,
            },
            200,
            function () {
                if (jName.length > 0) {
                    _this.text(jName).animate(
                        {
                            opacity: 1,
                        },
                        200
                    );
                    _this.data("jname", currentName);
                }
            }
        );
    });
}

function nextEpisode() {
    var e, t;
    $(".ep-item.active").is(":last-child") ? (e = (t = $(".ep-page-item.active").next()).data("page"),
        0 < t.length && (t.click(),
            $("#episodes-page-" + e + " .ep-item").first().click())) : 0 < (t = $(".ep-item.active").next()).length && t.click()
}

function prevEpisode() {
    var e, t;
    $(".ep-item.active").is(":first-child") ? (e = (t = $(".ep-page-item.active").prev()).data("page"),
        0 < t.length && (t.click(),
            $("#episodes-page-" + e + " .ep-item").last().click())) : 0 < (t = $(".ep-item.active").prev()).length && t.click()
}

function startLoggingContinueWatching() {
    if (watchingLogInterval) {
        clearInterval(watchingLogInterval);
    }
    if (watchingLogTimeout) {
        clearTimeout(watchingLogTimeout);
    }

    lastContinueWatchingKey = '';

    if (!isLoggedIn) {
        return;
    }

    watchingLogTimeout = setTimeout(() => {
        updateContinueWatching(animeId, epNumber, epId, currentVideoData.currentTime, currentVideoData.duration);
    }, hiContinueWatchingInitialDelay);

    watchingLogInterval = setInterval(() => {
        if (animeId && epId && isLoggedIn && !document.hidden) {
            updateContinueWatching(animeId, epNumber, epId, currentVideoData.currentTime, currentVideoData.duration);
        }
    }, hiContinueWatchingInterval);

    // Update continue watching when user leaves the page
    // window.addEventListener('beforeunload', function() {
    //     if (animeId && epId && isLoggedIn) {
    //         updateContinueWatching(animeId, epNumber, epId, currentVideoData.currentTime, currentVideoData.duration);
    //     }
    // });

    // // Update continue watching when page becomes hidden
    // document.addEventListener('visibilitychange', function() {
    //     if (document.hidden && animeId && epId && isLoggedIn) {
    //         updateContinueWatching(animeId, epNumber, epId, currentVideoData.currentTime, currentVideoData.duration);
    //     }
    // });
}

window.addEventListener('message', function (event) {
    // Handle specific event data
    if (event.data && typeof event.data === 'object') {
        const data = event.data;
        if (data.type === 'watching-log') {
            currentVideoData.currentTime = data.currentTime || 0;
            currentVideoData.duration = data.duration || 0;
            if (data.currentTime >= data.duration && parseInt(getUserSetting('auto_next', '0'))) {
                nextEpisode();
            }
            return;
        }
    }
});

function updateContinueWatching(animeId, episodeNumber, episodeId, time, duration) {
    if (!isLoggedIn || !animeId || !episodeId || continueWatchingRequestInFlight) {
        return;
    }

    time = parseInt(time, 10) || 0;
    duration = parseInt(duration, 10) || 0;

    if (duration > 0 && time < 5) {
        return;
    }

    const continueWatchingKey = [
        animeId,
        episodeNumber || 0,
        episodeId,
        Math.floor(time),
        duration
    ].join(':');
    if (continueWatchingKey === lastContinueWatchingKey) {
        return;
    }

    lastContinueWatchingKey = continueWatchingKey;
    continueWatchingRequestInFlight = true;

    $.ajax({
        url: user.rest_url + 'continue-watching',
        type: 'POST',
        contentType: 'application/json',
        data: JSON.stringify({
            anime_id: animeId,
            ep_id: episodeId,
            ep: episodeNumber,
            time: time,
            duration: duration
        }),
        success: (response) => {
            if (response && response.success) {
                console.log('Continue watching updated successfully');
            } else {
                console.error('Failed to update continue watching:', response);
            }
        },
        error: (xhr, status, error) => {
            console.error('REST error updating continue watching:', error);
        },
        complete: () => {
            continueWatchingRequestInFlight = false;
        }
    });
}
