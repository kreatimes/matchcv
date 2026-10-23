import 'package:file_picker/file_picker.dart';
import 'package:flutter/material.dart';
import 'package:url_launcher/url_launcher.dart';

import 'api.dart';

void main() => runApp(const MatchCvApp());

class MatchCvApp extends StatelessWidget {
  const MatchCvApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'MatchCV',
      debugShowCheckedModeBanner: false,
      theme: ThemeData(colorSchemeSeed: Colors.indigo, useMaterial3: true),
      darkTheme: ThemeData(colorSchemeSeed: Colors.indigo, brightness: Brightness.dark, useMaterial3: true),
      home: const HomePage(),
    );
  }
}

class HomePage extends StatefulWidget {
  const HomePage({super.key});

  @override
  State<HomePage> createState() => _HomePageState();
}

class _HomePageState extends State<HomePage> {
  final _province = TextEditingController();
  bool _loading = false;
  MatchResult? _result;

  @override
  void dispose() {
    _province.dispose();
    super.dispose();
  }

  Future<void> _pickAndMatch() async {
    final picked = await FilePicker.pickFiles(type: FileType.custom, allowedExtensions: ['pdf']);
    final file = picked.isEmpty ? null : picked.first;
    if (file == null || file.path == null) return;

    setState(() => _loading = true);
    try {
      final result = await Api.match(file.path!, file.name, province: _province.text.trim());
      setState(() => _result = result);
    } catch (e) {
      debugPrint('Error en match: $e');
      if (mounted) {
        ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(Api.errorMessage(e))));
      }
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    final result = _result;
    return Scaffold(
      appBar: AppBar(
        title: const Text('MatchCV'),
        actions: [
          if (result != null && !_loading)
            IconButton(
              tooltip: 'Probar con otro CV',
              icon: const Icon(Icons.refresh),
              onPressed: () => setState(() => _result = null),
            ),
        ],
      ),
      body: _loading
          ? const _Loading()
          : result == null
              ? _Start(province: _province, onPick: _pickAndMatch)
              : _Results(result: result),
    );
  }
}

class _Start extends StatelessWidget {
  final TextEditingController province;
  final VoidCallback onPick;

  const _Start({required this.province, required this.onPick});

  @override
  Widget build(BuildContext context) {
    final text = Theme.of(context).textTheme;
    return Center(
      child: SingleChildScrollView(
        padding: const EdgeInsets.all(24),
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            crossAxisAlignment: CrossAxisAlignment.stretch,
            children: [
              Icon(Icons.work_outline, size: 72, color: Theme.of(context).colorScheme.primary),
              const SizedBox(height: 16),
              Text('Encuentra ofertas que encajan contigo', style: text.headlineSmall, textAlign: TextAlign.center),
              const SizedBox(height: 8),
              Text(
                'Sube tu CV en PDF. La IA lo lee, busca ofertas y te dice cuánto encajas en cada una y qué te falta.',
                style: text.bodyMedium,
                textAlign: TextAlign.center,
              ),
              const SizedBox(height: 24),
              TextField(
                controller: province,
                textCapitalization: TextCapitalization.words,
                decoration: const InputDecoration(
                  labelText: 'Provincia (opcional)',
                  helperText: 'Si lo dejas vacío, se usa la de tu CV',
                  prefixIcon: Icon(Icons.place_outlined),
                  border: OutlineInputBorder(),
                ),
              ),
              const SizedBox(height: 16),
              FilledButton.icon(
                onPressed: onPick,
                icon: const Icon(Icons.upload_file),
                label: const Padding(padding: EdgeInsets.all(12), child: Text('Subir CV y buscar')),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _Loading extends StatelessWidget {
  const _Loading();

  @override
  Widget build(BuildContext context) {
    return const Center(
      child: Column(
        mainAxisSize: MainAxisSize.min,
        children: [
          CircularProgressIndicator(),
          SizedBox(height: 16),
          Text('Leyendo tu CV y buscando ofertas…'),
          SizedBox(height: 4),
          Text('Puede tardar unos segundos', style: TextStyle(fontSize: 12)),
        ],
      ),
    );
  }
}

class _Results extends StatelessWidget {
  final MatchResult result;

  const _Results({required this.result});

  @override
  Widget build(BuildContext context) {
    final p = result.profile;
    final text = Theme.of(context).textTheme;
    final details = [
      ?p.seniority,
      if (p.years != null) '${p.years} años exp.',
      ?p.province,
    ].join(' · ');

    return ListView(
      padding: const EdgeInsets.all(16),
      children: [
        Card(
          child: Padding(
            padding: const EdgeInsets.all(16),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Text('Tu perfil', style: text.labelLarge),
                const SizedBox(height: 4),
                Text(p.role, style: text.titleLarge),
                if (details.isNotEmpty) Text(details, style: text.bodyMedium),
                const SizedBox(height: 8),
                Wrap(
                  spacing: 6,
                  runSpacing: 6,
                  children: p.skills.take(10).map((s) => Chip(label: Text(s), visualDensity: VisualDensity.compact)).toList(),
                ),
              ],
            ),
          ),
        ),
        Padding(
          padding: const EdgeInsets.symmetric(vertical: 12),
          child: Text(
            result.matches.isEmpty
                ? 'No hemos encontrado ofertas para: ${p.keywords.join(', ')}'
                : '${result.offersFound} ofertas encontradas · las ${result.matches.length} que mejor encajan',
            style: text.titleSmall,
          ),
        ),
        ...result.matches.map((m) => _MatchCard(match: m)),
      ],
    );
  }
}

class _MatchCard extends StatelessWidget {
  final Match match;

  const _MatchCard({required this.match});

  @override
  Widget build(BuildContext context) {
    final o = match.offer;
    final subtitle = [
      ?o.company,
      ?o.city,
      if (o.remote) 'Remoto',
      ?o.salary,
    ].join(' · ');

    return Card(
      clipBehavior: Clip.antiAlias,
      child: ExpansionTile(
        leading: _ScoreBadge(score: match.score),
        title: Text(o.title),
        subtitle: Text(subtitle),
        childrenPadding: const EdgeInsets.fromLTRB(16, 0, 16, 12),
        expandedCrossAxisAlignment: CrossAxisAlignment.start,
        children: [
          if (match.reason.isNotEmpty) Text(match.reason),
          const SizedBox(height: 8),
          ...match.matches.map((s) => _Line(icon: Icons.check_circle, color: Colors.green, text: s)),
          ...match.missing.map((s) => _Line(icon: Icons.cancel, color: Colors.redAccent, text: s)),
          const SizedBox(height: 8),
          Row(
            children: [
              Text(o.source == 'adzuna' ? 'Jobs by Adzuna' : 'Fuente: ${o.source}',
                  style: Theme.of(context).textTheme.bodySmall),
              const Spacer(),
              if (o.url != null)
                TextButton.icon(
                  onPressed: () => launchUrl(Uri.parse(o.url!), mode: LaunchMode.externalApplication),
                  icon: const Icon(Icons.open_in_new, size: 18),
                  label: const Text('Ver oferta'),
                ),
            ],
          ),
        ],
      ),
    );
  }
}

class _ScoreBadge extends StatelessWidget {
  final int score;

  const _ScoreBadge({required this.score});

  @override
  Widget build(BuildContext context) {
    final color = score >= 80
        ? Colors.green
        : score >= 50
            ? Colors.orange
            : Colors.grey;
    return CircleAvatar(
      backgroundColor: color.withValues(alpha: 0.15),
      child: Text('$score', style: TextStyle(color: color, fontWeight: FontWeight.bold)),
    );
  }
}

class _Line extends StatelessWidget {
  final IconData icon;
  final Color color;
  final String text;

  const _Line({required this.icon, required this.color, required this.text});

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 2),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Icon(icon, size: 18, color: color),
          const SizedBox(width: 8),
          Expanded(child: Text(text)),
        ],
      ),
    );
  }
}
