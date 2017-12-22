package com.smartmobilefactory.epubreader.display.view;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Build;
import android.support.annotation.RequiresApi;
import android.util.AttributeSet;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.smartmobilefactory.epubreader.EpubViewSettings;
import com.smartmobilefactory.epubreader.display.EpubDisplayHelper;
import com.smartmobilefactory.epubreader.display.WebViewHelper;
import com.smartmobilefactory.epubreader.model.Epub;
import com.smartmobilefactory.epubreader.model.EpubFont;
import com.smartmobilefactory.epubreader.model.EpubLocation;
import com.smartmobilefactory.epubreader.utils.BaseDisposableObserver;

import java.lang.ref.WeakReference;
import java.util.concurrent.TimeUnit;

import io.reactivex.Observable;
import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.disposables.CompositeDisposable;
import io.reactivex.subjects.BehaviorSubject;
import nl.siegmann.epublib.domain.SpineReference;

public class EpubWebView extends WebView {

    public interface UrlInterceptor {
        boolean shouldOverrideUrlLoading(String url);
    }

    private final CompositeDisposable settingsCompositeDisposable = new CompositeDisposable();
    private final CompositeDisposable loadEpubCompositeDisposable = new CompositeDisposable();

    private UrlInterceptor urlInterceptor;
    private WebViewHelper webViewHelper = new WebViewHelper(this);
    private BehaviorSubject<Boolean> isReady = BehaviorSubject.createDefault(false);

    private WeakReference<EpubViewSettings> settingsWeakReference;

    public EpubWebView(Context context) {
        super(context);
        init();
    }

    public EpubWebView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public EpubWebView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    public EpubWebView(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    @SuppressLint("SetJavaScriptEnabled")
    private void init() {
        setWebViewClient(client);
        getSettings().setJavaScriptEnabled(true);
        getSettings().setAllowUniversalAccessFromFileURLs(true);
        getSettings().setAllowFileAccess(true);
        setVerticalScrollBarEnabled(false);
        setHorizontalScrollBarEnabled(false);
        setBackgroundColor(Color.TRANSPARENT);
    }

    @SuppressLint({"JavascriptInterface", "AddJavascriptInterface"})
    public void setInternalBridge(InternalEpubBridge bridge) {
        addJavascriptInterface(bridge, "internalBridge");
    }

    public Observable<Boolean> isReady() {
        return isReady;
    }

    public void gotoLocation(EpubLocation location) {
        isReady.filter(isReady -> isReady)
                .take(1)
                .doOnNext(isReady -> {
                    if (location instanceof EpubLocation.IdLocation) {
                        webViewHelper.callJavaScriptMethod("scrollToElementById", ((EpubLocation.IdLocation) location).id());
                    } else if (location instanceof EpubLocation.XPathLocation) {
                        webViewHelper.callJavaScriptMethod("scrollToElementByXPath", ((EpubLocation.XPathLocation) location).xPath());
                    } else if (location instanceof EpubLocation.RangeLocation) {
                        webViewHelper.callJavaScriptMethod("scrollToRangeStart", ((EpubLocation.RangeLocation) location).start());
                    }
                })
                .subscribe(new BaseDisposableObserver<>());
    }

    public void setUrlInterceptor(UrlInterceptor interceptor) {
        this.urlInterceptor = interceptor;
    }

    public void bindToSettings(EpubViewSettings settings) {
        if (settings == null) {
            return;
        }
        settingsWeakReference = new WeakReference<>(settings);
        settingsCompositeDisposable.clear();

        settings.anySettingHasChanged()
                .doOnNext(setting -> {
                    switch (setting) {
                        case FONT:
                            setFont(settings.getFont());
                            break;
                        case FONT_SIZE:
                            setFontSizeSp(settings.getFontSizeSp());
                            break;
                        case JAVASCRIPT_BRIDGE:
                            setJavascriptBridge(settings.getJavascriptBridge());
                            break;
                    }
                })
                .debounce(100, TimeUnit.MILLISECONDS)
                .observeOn(AndroidSchedulers.mainThread())
                .doOnNext(setting -> {
                    callJavascriptMethod("updateFirstVisibleElement");
                })
                .subscribeWith(new BaseDisposableObserver<>())
                .addTo(settingsCompositeDisposable);

        setFontSizeSp(settings.getFontSizeSp());
        setJavascriptBridge(settings.getJavascriptBridge());
        setFont(settings.getFont());

    }

    public void loadEpubPage(Epub epub, SpineReference spineReference, EpubViewSettings settings) {
        if (settings == null) {
            return;
        }
        loadEpubCompositeDisposable.clear();
        settings.anySettingHasChanged()
                // reload html when some settings changed
                .filter(setting -> setting == EpubViewSettings.Setting.CUSTOM_FILES)
                .startWith(EpubViewSettings.Setting.CUSTOM_FILES)
                .flatMap(setting -> EpubDisplayHelper.loadHtmlData(this, epub, spineReference, settings)
                        .toObservable())
                .subscribeWith(new BaseDisposableObserver<>())
                .addTo(loadEpubCompositeDisposable);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (settingsWeakReference != null && settingsWeakReference.get() != null) {
            bindToSettings(settingsWeakReference.get());
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        settingsCompositeDisposable.clear();
        loadEpubCompositeDisposable.clear();
    }

    @SuppressLint({"JavascriptInterface", "AddJavascriptInterface"})
    private void setJavascriptBridge(Object bridge) {
        addJavascriptInterface(bridge, "bridge");
    }

    private void setFont(EpubFont font) {
        if (font.uri() == null && font.name() == null) {
            return;
        }
        isReady.filter(isReady -> isReady)
                .take(1)
                .subscribe(isReady -> {
                    if (font.uri() == null) {
                        webViewHelper.callJavaScriptMethod("setFontFamily", font.name());
                    } else {
                        webViewHelper.callJavaScriptMethod("setFont", font.name(), font.uri());
                    }
                });
    }

    public void callJavascriptMethod(String name, Object... args) {
        webViewHelper.callJavaScriptMethod(name, args);
    }

    private void setFontSizeSp(int fontSizeSp) {
        getSettings().setDefaultFontSize(fontSizeSp);
        getSettings().setMinimumFontSize(fontSizeSp);
        getSettings().setDefaultFixedFontSize(fontSizeSp);
        getSettings().setMinimumLogicalFontSize(fontSizeSp);
    }

    private final WebViewClient client = new WebViewClient() {
        @Override
        public void onPageStarted(WebView view, String url, Bitmap favicon) {
            super.onPageStarted(view, url, favicon);
            isReady.onNext(false);
        }

        @Override
        public void onPageFinished(WebView view, String url) {
            super.onPageFinished(view, url);
            isReady.onNext(true);
        }

        @Override
        public boolean shouldOverrideUrlLoading(WebView view, String url) {
            if (urlInterceptor != null) {
                return urlInterceptor.shouldOverrideUrlLoading(url);
            }
            return false;
        }

        @TargetApi(Build.VERSION_CODES.LOLLIPOP)
        @Override
        public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            return shouldOverrideUrlLoading(view, request.getUrl().toString());
        }

    };

}
