const texts = ['Press Enter To Find Externally','Find Movies/Series Here.', 'Like "Avengers End Game".'];
const input = document.querySelector('#searchbox');
const animationWorker = function (input, texts) {
    this.input = input;
    this.defaultPlaceholder = this.input.getAttribute('placeholder');
    this.texts = texts;
    this.curTextNum = 0;
    this.curPlaceholder = '';
    this.blinkCounter = 0;
    this.animationFrameId = 0;
    this.animationActive = false;
    this.input.setAttribute('placeholder', this.curPlaceholder);

    this.switch = (timeout) => {
        this.input.classList.add('imitatefocus');
        setTimeout(
            () => {
                this.input.classList.remove('imitatefocus');
                if (this.curTextNum == 0)
                    this.input.setAttribute('placeholder', this.defaultPlaceholder);
                else
                    this.input.setAttribute('placeholder', this.curPlaceholder);

                setTimeout(
                    () => {
                        this.input.setAttribute('placeholder', this.curPlaceholder);
                        if (this.animationActive)
                            this.animationFrameId = window.requestAnimationFrame(this.animate)
                    },
                    timeout);
            },
            timeout);
    }

    this.animate = () => {
        if (!this.animationActive) return;
        let curPlaceholderFullText = this.texts[this.curTextNum];
        let timeout = 900;
        if (this.curPlaceholder == curPlaceholderFullText + '|' && this.blinkCounter == 3) {
            this.blinkCounter = 0;
            this.curTextNum = (this.curTextNum >= this.texts.length - 1) ? 0 : this.curTextNum + 1;
            this.curPlaceholder = '|';
            this.switch(2000);
            return;
        } else if (this.curPlaceholder == curPlaceholderFullText + '|' && this.blinkCounter < 3) {
            this.curPlaceholder = curPlaceholderFullText;
            this.blinkCounter++;
        } else if (this.curPlaceholder == curPlaceholderFullText && this.blinkCounter < 3) {
            this.curPlaceholder = this.curPlaceholder + '|';
        } else {
            this.curPlaceholder = curPlaceholderFullText
                .split('')
                .slice(0, this.curPlaceholder.length + 0)
                .join('') + '|';
            timeout = 100;
        }
        this.input.setAttribute('placeholder', this.curPlaceholder);
        setTimeout(
            () => {
                if (this.animationActive) this.animationFrameId = window.requestAnimationFrame(this.animate)
            },
            timeout);
    }

    this.stop = () => {
        this.animationActive = false;
        window.cancelAnimationFrame(this.animationFrameId);
    }

    this.start = () => {
        this.animationActive = true;
        this.animationFrameId = window.requestAnimationFrame(this.animate);
        return this;
    }
}

document.addEventListener("DOMContentLoaded", () => {
    let aw = new animationWorker(input, texts).start();
    input.addEventListener("focus", (e) => aw.stop());
    input.addEventListener("blur", (e) => {
        aw = new animationWorker(input, texts);
        if (e.target.value == '') setTimeout(aw.start, 2000);
    });
});

$(document).ready(function () {
    $('.search-container input[type="text"]').on("keyup input", $.debounce(300, function () {
        var mt = $('#category :selected').val();
        var t = $(this).val(),
            e = $(this).siblings(".result");
        t.length ? $.post("/search", {
            term: t,
            types: mt
        }).done(function (t) {
            e.html(t)
        }) : e.empty()

    }));
    document.addEventListener("touchstart", function () {
    }, true);

});


$("#searchbox").keyup(function (event) {
    if (event.keyCode === 13) {
        const mtt = $('#category :selected').val();
        const tt = document.getElementById("searchbox").value;
        window.location.href = "/" + mtt + "/find/" + tt;
        console.log('Working');
    }
});

