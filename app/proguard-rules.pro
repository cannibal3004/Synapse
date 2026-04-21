# Copyright (c) 2023 by OpenAI (https://openai.com)
#
# Copyright 2023 Google LLC
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# Add any specific rules for your app here.

# Retrofit
-keepattributes Signature
-keepattributes Exceptions
-keepclassmembers interfaces!** {
    @retrofit2.http.<methods>;
}
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# Gson
-keepattributes Signature
-keepattributes *Annotation*
-dontwarn sun.misc.**
-keep class com.aiassistant.data.model.** { *; }
-keep class com.aiassistant.domain.model.** { *; }

# Room
-keep class com.aiassistant.data.database.** { *; }

# Coroutines
-keepclassmembers fn.*CoroutineSuspend { *; }
