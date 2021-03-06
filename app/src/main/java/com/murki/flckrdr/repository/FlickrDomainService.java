package com.murki.flckrdr.repository;

import android.content.Context;
import android.util.Log;

import com.fernandocejas.frodo.annotation.RxLogObservable;
import com.murki.flckrdr.ITimestampedView;
import com.murki.flckrdr.model.RecentPhotosResponse;
import com.murki.flckrdr.viewmodel.FlickrModelToVmMapping;
import com.murki.flckrdr.viewmodel.FlickrCardVM;

import java.util.List;

import rx.Observable;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.schedulers.Schedulers;
import rx.schedulers.Timestamped;

public class FlickrDomainService {

    private static final String CLASSNAME = FlickrDomainService.class.getCanonicalName();

    private final FlickrNetworkRepository flickrNetworkRepository;
    private final FlickrDiskRepository flickrDiskRepository;

    public FlickrDomainService(Context context) {
        flickrNetworkRepository = new FlickrNetworkRepository(); // TODO: Inject Singleton
        flickrDiskRepository = new FlickrDiskRepository(context); // TODO: Inject Singleton
    }

    @RxLogObservable
    public Observable<Timestamped<List<FlickrCardVM>>> getRecentPhotos(ITimestampedView timestampedView) {
        return getMergedPhotos()
                .onErrorReturn(new Func1<Throwable, Timestamped<RecentPhotosResponse>>() {
                    @Override
                    public Timestamped<RecentPhotosResponse> call(Throwable throwable) {
                        Log.e(CLASSNAME, "Error while fetching data. Swallowing the exception.", throwable);
                        return null; // We return null since we know our filter will ignore null values.
                    }
                })
                .filter(getRecentPhotosFilter(timestampedView))
                .map(FlickrModelToVmMapping.instance());
    }

    @RxLogObservable
    private Observable<Timestamped<RecentPhotosResponse>> getMergedPhotos() {
        return Observable.merge(
                flickrDiskRepository.getRecentPhotos().subscribeOn(Schedulers.io()),
                flickrNetworkRepository.getRecentPhotos().timestamp().doOnNext(new Action1<Timestamped<RecentPhotosResponse>>() {
                    @Override
                    public void call(Timestamped<RecentPhotosResponse> recentPhotosResponse) {
                        Log.d(CLASSNAME, "flickrApiRepository.getRecentPhotos().doOnNext() - Saving photos to disk - thread=" + Thread.currentThread().getName());
                        flickrDiskRepository.savePhotos(recentPhotosResponse);
                    }
                }).subscribeOn(Schedulers.io())
        );
    }

    private Func1<Timestamped<RecentPhotosResponse>, Boolean> getRecentPhotosFilter(final ITimestampedView timestampedView) {
        return new Func1<Timestamped<RecentPhotosResponse>, Boolean>() {
            @Override
            public Boolean call(Timestamped<RecentPhotosResponse> recentPhotosResponseTimestamped) {

                StringBuilder logMessage = new StringBuilder("getMergedPhotos().filter() - Filtering results");
                if (recentPhotosResponseTimestamped == null) {
                    logMessage.append(", recentPhotosResponseTimestamped is null");
                } else {
                    logMessage.append(", timestamps=").append(recentPhotosResponseTimestamped.getTimestampMillis()).append(">").append(timestampedView.getViewDataTimestampMillis()).append("?");
                }
                logMessage.append(", thread=").append(Thread.currentThread().getName());
                Log.d(CLASSNAME, logMessage.toString());

                // filter it
                // if result is null - ignore it
                // if timestamp of new arrived (emission) data is less than timestamp of already displayed data — ignore it.
                return recentPhotosResponseTimestamped != null
                        && recentPhotosResponseTimestamped.getValue() != null
                        && recentPhotosResponseTimestamped.getValue().photos != null
                        && recentPhotosResponseTimestamped.getTimestampMillis() > timestampedView.getViewDataTimestampMillis();
            }
        };
    }
}
