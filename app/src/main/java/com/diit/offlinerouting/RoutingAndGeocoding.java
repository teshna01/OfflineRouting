package com.diit.offlinerouting;

import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Environment;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MotionEvent;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.esri.android.map.GraphicsLayer;
import com.esri.android.map.MapOnTouchListener;
import com.esri.android.map.MapView;
import com.esri.android.map.TiledLayer;
import com.esri.android.map.ags.ArcGISLocalTiledLayer;
import com.esri.core.geometry.Geometry;
import com.esri.core.geometry.Point;
import com.esri.core.geometry.SpatialReference;
import com.esri.core.map.Graphic;
import com.esri.core.symbol.SimpleLineSymbol;
import com.esri.core.symbol.SimpleMarkerSymbol;
import com.esri.core.tasks.geocode.Locator;
import com.esri.core.tasks.geocode.LocatorReverseGeocodeResult;
import com.esri.core.tasks.na.NAFeaturesAsFeature;
import com.esri.core.tasks.na.Route;
import com.esri.core.tasks.na.RouteDirection;
import com.esri.core.tasks.na.RouteParameters;
import com.esri.core.tasks.na.RouteResult;
import com.esri.core.tasks.na.RouteTask;
import com.esri.core.tasks.na.StopGraphic;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class RoutingAndGeocoding extends AppCompatActivity {
    // Define ArcGIS Elements
    MapView mMapView;
    final String extern = Environment.getExternalStorageDirectory().getPath();
    final String tpkPath = "/ArcGIS/samples/OfflineRouting/SanDiego.tpk";
    TiledLayer mTileLayer = new ArcGISLocalTiledLayer(extern + tpkPath);
    GraphicsLayer mGraphicsLayer = new GraphicsLayer(GraphicsLayer.RenderingMode.DYNAMIC);

    RouteTask mRouteTask = null;
    NAFeaturesAsFeature mStops = new NAFeaturesAsFeature();

    Locator mLocator = null;
    View mCallout = null;
    Spinner dSpinner;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_routing_and_geocoding);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        // 显示导航信息的spinner
        dSpinner = (Spinner) findViewById(R.id.directionsSpinner);
        dSpinner.setEnabled(false);

        mMapView = (MapView) findViewById(R.id.map);

        // 基础图层和绘制的GraphicsLayer
        mMapView.addLayer(mTileLayer);
        mMapView.addLayer(mGraphicsLayer);

        // 使用本地离线数据初始化RouteTask
        initializeRoutingAndGeocoding();
        mMapView.setOnTouchListener(new TouchListener(RoutingAndGeocoding.this, mMapView));
    }
    private void initializeRoutingAndGeocoding() {
        // 放在子线程处理
        new Thread(new Runnable() {
            @Override
            public void run() {
                // 地理编码器和数据层geodatabase
                String locatorPath = "/ArcGIS/samples/OfflineRouting/Geocoding/SanDiego_StreetAddress.loc";
                String networkPath = "/ArcGIS/samples/OfflineRouting/Routing/RuntimeSanDiego.geodatabase";
                String networkName = "Streets_ND";

                // 调用地理编码器和geodatabase 进行路线规划
                try {
                    mLocator = Locator.createLocalLocator(extern + locatorPath);
                    mRouteTask = RouteTask.createLocalRouteTask(extern + networkPath, networkName);
                } catch (Exception e) {
                    popToast("Error while initializing :" + e.getMessage(), true);
                    e.printStackTrace();
                }
            }
        }).start();
    }

    class TouchListener extends MapOnTouchListener {
        private int routeHandle = -1;
        @Override
        public void onLongPress(MotionEvent point) {
            // 长按清楚
            mStops.clearFeatures();
            mGraphicsLayer.removeAll();
            mMapView.getCallout().hide();
        }

        @Override
        public boolean onSingleTap(MotionEvent point) {
            // 单击选择导航点
            if (mLocator == null) {
                popToast("Locator uninitialized", true);
                return super.onSingleTap(point);
            }

            Point mapPoint = mMapView.toMapPoint(point.getX(), point.getY());
            Graphic graphic = new Graphic(mapPoint, new SimpleMarkerSymbol(Color.BLUE, 20, SimpleMarkerSymbol.STYLE.DIAMOND));
            mGraphicsLayer.addGraphic(graphic);
            String stopAddress = "";
            try {
                // 把point 反编译成地址
                //空间参考要和基础地图一样
                SpatialReference mapRef = mMapView.getSpatialReference();
                LocatorReverseGeocodeResult result = mLocator.reverseGeocode(mapPoint, 50, mapRef, mapRef);

                // 地址格式化
                StringBuilder address = new StringBuilder();
                if (result != null && result.getAddressFields() != null) {
                    Map<String, String> addressFields = result.getAddressFields();
                    address.append(String.format("%s\n%s, %s %s", addressFields.get("Street"), addressFields.get("City"),
                            addressFields.get("State"), addressFields.get("ZIP")));
                }
                // 把Point 编译成地址后弹出框里显示
                stopAddress = address.toString();
                showCallout(stopAddress, mapPoint);

            } catch (Exception e) {
                Log.v("Reverse Geocode", e.getMessage());
            }
            //导航点点击事件
            StopGraphic stop = new StopGraphic(graphic);
            stop.setName(stopAddress.toString());
            mStops.addFeature(stop);
            return true;
        }

        @Override
        public boolean onDoubleTap(MotionEvent point) {
            // 若导航解析器没有初始化则返回默认双击事件对象
            if (mRouteTask == null) {
                popToast("RouteTask uninitialized.", true);
                return super.onDoubleTap(point);
            }

            try {
               //导航点跟当前地图的空间参考要一样
                SpatialReference mapRef = mMapView.getSpatialReference();
                RouteParameters params = mRouteTask.retrieveDefaultRouteTaskParameters();
                params.setOutSpatialReference(mapRef);
                mStops.setSpatialReference(mapRef);

               //设置导航点
                params.setStops(mStops);
                params.setReturnDirections(true);

                //规划路线
                RouteResult results = mRouteTask.solve(params);


                // 返回结果
                Route result = results.getRoutes().get(0);

                // 清楚之前的路线
                if (routeHandle != -1)
                    mGraphicsLayer.removeGraphic(routeHandle);

                // 新路线绘制到地图上
                Geometry geom = result.getRouteGraphic().getGeometry();
                routeHandle = mGraphicsLayer.addGraphic(new Graphic(geom, new SimpleLineSymbol(0x99990055, 5)));
                mMapView.getCallout().hide();

                // 从结果中取出拐点说明信息
                List<RouteDirection> directions = result.getRoutingDirections();

                dSpinner.setEnabled(true);

               //格式化说明信息
                List<String> formattedDirections = new ArrayList<String>();
                for (int i = 0; i < directions.size(); i++) {
                    RouteDirection direction = directions.get(i);
                    formattedDirections.add(String.format("%s\nGo %.2f %s For %.2f Minutes", direction.getText(),
                            direction.getLength(), params.getDirectionsLengthUnit().name(), direction.getMinutes()));
                }

                // 加摘要信息
                formattedDirections.add(0, String.format("Total time: %.2f Mintues", result.getTotalMinutes()));

                //将格式化后的说明信息添加到apinner
                ArrayAdapter<String> adapter = new ArrayAdapter<String>(getApplicationContext(),
                        android.R.layout.simple_spinner_item, formattedDirections);
                adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                dSpinner.setAdapter(adapter);

                // spinner 点击列表时地图上展示对应的路线段
                dSpinner.setOnItemSelectedListener(new DirectionsItemListener(directions));

            } catch (Exception e) {
                popToast("Solve Failed: " + e.getMessage(), true);
                e.printStackTrace();
            }
            return true;
        }

        public TouchListener(Context context, MapView view) {
            super(context, view);
        }
    }
    class DirectionsItemListener implements AdapterView.OnItemSelectedListener {
        private List<RouteDirection> mDirections;
        public DirectionsItemListener(List<RouteDirection> directions) {
            mDirections = directions;
        }

        @Override
        public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
            // 摘要信息
            if (mDirections != null && pos > 0 && pos <= mDirections.size())
                mMapView.setExtent(mDirections.get(pos - 1).getGeometry());
        }

        @Override
        public void onNothingSelected(AdapterView<?> arg0) {
        }
    }

    private void showCallout(String text, Point location) {
        if (mCallout == null) {
            LayoutInflater inflater = (LayoutInflater) getApplication().getSystemService(Context.LAYOUT_INFLATER_SERVICE);
            mCallout = inflater.inflate(R.layout.callout, null);
        }

        // 设置地名地址弹出框
        ((TextView) mCallout.findViewById(R.id.calloutText)).setText(text);
        mMapView.getCallout().show(location, mCallout);
        mMapView.getCallout().setMaxWidth(700);
    }

    private void popToast(final String message, final boolean show) {
        // 在主线程显示提示框
        if (!show)
            return;

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(RoutingAndGeocoding.this, message, Toast.LENGTH_SHORT).show();
            }
        });
    }


}
