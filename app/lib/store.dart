import 'dart:convert';

import 'package:shared_preferences/shared_preferences.dart';

import 'api.dart';

// Búsqueda favorita: la URL del portal con los filtros que el usuario dejó puestos
class SavedSearch {
  final String name, role, portal, url;

  SavedSearch({required this.name, required this.role, required this.portal, required this.url});

  SavedSearch.fromJson(Map<String, dynamic> j)
      : name = j['name'],
        role = j['role'],
        portal = j['portal'],
        url = j['url'];

  Map<String, dynamic> toJson() => {'name': name, 'role': role, 'portal': portal, 'url': url};
}

// Todo se guarda en el móvil: el backend no guarda nada del usuario
class Store {
  static Future<Profile?> loadProfile() async {
    final prefs = await SharedPreferences.getInstance();
    final json = prefs.getString('profile');
    return json == null ? null : Profile.fromJson(jsonDecode(json));
  }

  static Future<void> saveProfile(Profile profile) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('profile', jsonEncode(profile.json));
  }

  static Future<List<SavedSearch>> loadSearches() async {
    final prefs = await SharedPreferences.getInstance();
    final json = prefs.getString('searches');
    if (json == null) return [];
    return (jsonDecode(json) as List).map((s) => SavedSearch.fromJson(s)).toList();
  }

  static Future<void> saveSearches(List<SavedSearch> searches) async {
    final prefs = await SharedPreferences.getInstance();
    await prefs.setString('searches', jsonEncode(searches.map((s) => s.toJson()).toList()));
  }

  // Guarda o sustituye (mismo nombre) y la deja la primera
  static Future<void> addSearch(SavedSearch search) async {
    final list = await loadSearches();
    list.removeWhere((s) => s.name.toLowerCase() == search.name.toLowerCase());
    list.insert(0, search);
    await saveSearches(list);
  }
}
