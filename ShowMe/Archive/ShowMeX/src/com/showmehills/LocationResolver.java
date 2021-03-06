/*
    Copyright 2012 Nik Cain nik@showmehills.com
    
    This file is part of ShowMeHills.

    ShowMeHills is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    ShowMeHills is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with ShowMeHills.  If not, see <http://www.gnu.org/licenses/>.
    
    This source originated from mixare, another GPL project.
 */

package com.showmehills;

import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.Bundle;

public class LocationResolver implements LocationListener 
{
	String provider;
	private RapidGPSLock locationMgrImpl;
	private LocationManager lm;
	
	public LocationResolver(LocationManager lm, String provider, RapidGPSLock locationMgrImpl){
		this.lm = lm;
		this.provider = provider;
		this.locationMgrImpl = locationMgrImpl;
	}
	public void onLocationChanged(Location location) {
		lm.removeUpdates(this);
		locationMgrImpl.locationCallback(provider);
	}

	public void onProviderDisabled(String provider) {
	}

	public void onProviderEnabled(String provider) {
	}

	public void onStatusChanged(String provider, int status, Bundle extras) {
	}

}
